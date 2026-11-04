# R01 제한 재시도와 DLQ 격리

2026-11-04 사용자 T06 선택: **재시도 후 DLQ 격리, DLQ 전송 확인 후 원본 offset 진행·DLQ 중복 허용**.
DLQ 기록과 offset에 Kafka transaction은 도입하지 않는다. 아직 설계이며 runtime에 적용하지 않았다.

## 처리 흐름

```text
원본 record 수신
  → Inbox + 모든 구매 활동의 DB TX
      ├─ commit 또는 같은 완료 사실 확인 → listener ack
      └─ 실패 → 이번 DB TX rollback
                  → 제한 재시도
                      ├─ 성공 → listener ack
                      └─ 소진 → DLQ 전송
                                  ├─ broker 확인 → 원본 offset n+1 진행
                                  └─ 실패/timeout → 원본 offset 유지·소비 중단·원인 노출
```

DLQ 격리는 업무 실패를 보관하고 소비를 계속할 수 있게 하는 단계다.
DLQ에 갔다고 완료 Inbox/구매 활동을 만들거나 기존 결제를 실패로 돌리지 않는다.
DB commit 뒤 ack 오류처럼 실제 완료 사실이 이미 남은 경우도 있으므로 DLQ 존재만으로 DB 결과를 단정하지 않는다.

## 초기 정책 제안 · 사용자에게 확정받은 수치는 아님

- 일시적 DB 오류/기타 처리 오류: 최초 1회 + 추가 최대 2회, 추가 시도 사이 1초.
- 형식 오류·지원 밖 schema·고정 사실 충돌처럼 재시도로 바뀌지 않는 입력: 반복하지 않고 DLQ 격리.
- 각 DB 시도는 새 전체 TX다. 실패한 TX 안에서 재시도하지 않는다.
- DLQ는 `order-events.purchase-activity.dlq`를 제안하며 테스트는 원본과 함께 고유 이름을 사용한다.
- 원본 partition을 유지한다면 DLQ도 원본 이상 partition 수로 준비한다. 두 topic은 자동 삭제/재생성하지 않는다.
- DLQ 발행 실패/확인 timeout에는 완료로 반환하지 않는다. 해당 listener의 중단 경로를 명시적으로 연결한다.
  프레임워크 기본 recover 실패 동작만으로 즉시 중단된다고 가정하지 않는다.
- 긴 DB 장애에서는 DLQ 미해결 건이 쌓일 수 있다. 정상 offset 진행과 별도로 DLQ backlog를 관찰한다.

## 격리 보장과 남는 공백

```text
DLQ broker 확인
   ↓ 여기서 중단 가능
원본 group offset n+1 commit
```

위 사이에 중단되면 같은 원본 record가 재전달되고 DLQ에도 여러 번 기록될 수 있다. 이는 선택한 방식의 허용 중복이다.
eventId가 유효하면 사건 ID로, 파싱이 불가능한 입력이면 원본 topic·partition·offset으로 같은 격리 원본을 추적한다.
원본 위치·consumer group·key/value·안전한 오류 분류를 보존하고 반복 격리 기록 수를 구매 수로 세지 않는다.
DLQ 전송 timeout도 수신 0회를 뜻하지 않는다. 원본 offset은 유지하므로 이후 중복 격리가 가능하다.

Kafka transaction 대안은 DLQ record와 원본 offset을 Kafka 안에서 묶는 선택이다.
별도 MySQL commit까지 같은 원자성으로 묶는 보장은 아니므로 어느 방식이든 Inbox는 유지한다.
근거: [Spring Kafka 3.3 transactions](https://docs.spring.io/spring-kafka/reference/3.3/kafka/transactions.html).

## Spring Kafka 연결 제안

전용 record factory에 DefaultErrorHandler와 DeadLetterPublishingRecoverer를 연결한다.
recoverer는 send 결과 확인·실패 예외·유한 대기를 명시하고, MANUAL_IMMEDIATE의 recovered offset 설정을 사용한다.
컨테이너의 ackAfterHandle과 recoverer의 offset 처리를 함께 검사하며 기본값만으로 누락 방지를 주장하지 않는다.
기본 예외 헤더의 원문 snippet/stack 내용을 무조건 복사하지 않고 안전한 오류 분류와 원본 위치를 남긴다.
근거: [Spring Kafka 3.3 error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

구현에서 사용할 후보 설정은 다음과 같으며 실제 BOM 버전과 broker 검사가 최종 기준이다.

- DefaultErrorHandler + FixedBackOff(1000, 2), 영구 입력 오류는 not-retryable로 분류.
- DeadLetterPublishingRecoverer의 failIfSendResultIsError와 send-result 대기 한도를 명시.
- commitRecovered=true, ackAfterHandle=false를 후보로 두고 실제 n+1 진행 조건을 검증.
- recover 실패 시 예외 유지와 해당 listener의 비동기 중단을 연결해 빠른 무한 격리 재시도를 막음.

## 재처리와 관찰 · 초기 운영 제안

R01에서는 자동 DLQ worker를 추가하지 않고 로컬 도구에서 대상 DLQ 위치를 명시해 재처리하는 작은 경로를 제안한다.
원인을 먼저 수정하고 원래 UUID·confirmedAt·key·payload를 그대로 원본 topic에 재발행한다.
자동 무한 재발행이나 새로운 UUID 생성, 임의 payload 수정은 하지 않는다.
유효하지 않은 payload는 사실의 원본과 수정 책임을 확인하기 전 그대로 미해결 상태로 남긴다.
이미 처리된 사건이 재처리되면 Inbox가 no-op 처리한다. 충돌 사건은 기존 사실을 덮어쓰지 않는다.
도구는 공개 API로 만들지 않고 원본/DLQ 위치와 재발행 broker metadata·실제 Inbox 결과를 기록한다.

필수 관찰은 소비 재시도·DLQ 성공/실패·원본 위치·DLQ 미해결 backlog·재처리 결과다.
DLQ consumer lag가 낮아졌다는 사실만으로 구매 활동 완료라고 표시하지 않는다.

## 추가 검증

| 장면 | 기대 |
|---|---|
| 일시 오류 뒤 재시도 성공 | Inbox/활동 한 번, DLQ 없음 |
| DB 저장 실패의 재시도 소진 | 이번 TX의 완료 기록 없음, DLQ broker 확인 후 원본 offset n+1 |
| 잘못된 형식/충돌 | 기존 사실 불변, 불필요한 반복 없이 격리 |
| DLQ 전송 실패/timeout | 원본 offset 미진행, 해당 소비 중단/오류 표시 |
| DLQ 확인 후 offset commit 전 중단 | 같은 원본 재수신과 DLQ 중복 허용, 유효 업무 중복 없음 |
| 다음 정상 record | 격리 확인 후 정상 소비 진행 |
| 원인 수정 후 재처리 | 같은 UUID/내용으로 Inbox/활동 생성 또는 no-op |
| 같은 DLQ 대상 두 번 재처리 | 전달은 반복돼도 Inbox/구매 활동 증가 없음 |

재시도 횟수는 프로세스 재시작까지 보존되는 영속 총한도가 아니다. 재시작 후 같은 record를 다시 시도할 수 있다.
모든 확인은 실제 broker 기록·group offset·새 DB 조회로 수행한다.
