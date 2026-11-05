# 7주차 설계 — 주문 확정 사실의 보존과 재전달

이 문서는 현재 구현 상태를 설명한다. 실행 결과는 [구현 기록](implementation-log.md), 결정의 근거는 [ADR 0063](../adr/0063-record-order-confirmation-outbox-in-tx-c.md)~[0066](../adr/0066-keep-outbox-and-dedup-records-without-automatic-deletion.md)을 따른다.

## 현재 흐름

```text
TX B: 검증한 PG 결과·이력 저장 → commit
TX C: 저장 결과 확인 → 최초 주문 확정·예약 소비·Outbox PENDING → commit
TX 밖: relay가 재시도 시각이 된 PENDING을 찾아 시도 수 저장 → 저장값 전송 → broker 확인
별도 TX: Outbox PUBLISHED 저장
```

after-commit 직접 전송은 제거했다. 평소 발행과 재발행 모두 relay 한 경로를 쓴다.

## 트랜잭션 경계

| 경우 | 주문·예약 | Outbox | TX B 결과 |
| --- | --- | --- | --- |
| 최초 SUCCESS 적용 성공 | CONFIRMED·CONSUMED | PENDING 1행 | SUCCESS 유지 |
| 같은 결과 재적용·callback·reconcile·동시 적용 | 변경 없음 | 1행 유지, 내용 불변 | 유지 |
| PENDING·UNKNOWN·FAILED | 기존 전이 정책 | 0행 | 관찰 결과대로 |
| 직렬화·insert 실패, insert 뒤 TX 실패 | TX 직전 PAYMENT_PENDING·RESERVED | 새 행 없음 | SUCCESS 유지 |

rollback은 주문을 없애지 않는다. 주문 행은 TX C 직전 상태로 남고, TX B의 저장 결과로 PG create·조회 없이 TX C를 다시 실행한다. 이미 기록된 TX B의 PG 결과는 TX C 실패로 되돌아가지 않으므로 과제 예시의 "PG 성공 사실도 같은 TX"와 범위가 다르다([ADR 0063](../adr/0063-record-order-confirmation-outbox-in-tx-c.md)).

## 모델

`OrderOutbox`(`order_outbox`)가 전달 대상을 보존한다.

| 필드 | 값·규칙 |
| --- | --- |
| `eventId` | `order-confirmed:v1:{orderId}`, DB 유일 제약, 변경 불가 |
| `orderId`·`eventType`·`schemaVersion` | 주문 ID, `OrderConfirmed`, `1`, 변경 불가 |
| `messageKey` | 주문 ID 문자열, 변경 불가 |
| `payload` | TX C에서 직렬화한 `OrderConfirmedMessage` JSON, 변경 불가 |
| `status` | 생성 시 PENDING, broker 확인 저장 뒤 PUBLISHED |
| `attemptCount`·`nextAttemptAt`·`lastError` | 생성 시 0·최초 확정 시각·없음 |
| `createdAt`·`publishedAt` | 기록 시각·발행 확인 저장 시각. 생성 시 `publishedAt`은 없음 |

조회 인덱스 `idx_order_outbox_due(status, next_attempt_at, id)`는 relay의 재시도 대상 조회에 쓴다. backlog가 큰 조건의 실행 계획은 측정하지 않았다.

## Relay 결과별 상태

`PUBLISHED`는 broker 성공을 확인해 저장했다는 뜻이다. 구매 활동 반영은 consumer의 완료 기록과 상품별 활동으로 따로 확인한다.

relay는 같은 사건을 다시 보낼 수 있으므로 중복 효과는 consumer가 막는다. consumer는 W6에서 만든 **Idempotent Consumer** 구조를 그대로 쓴다.

| 겹 | 장치 | 막는 것 |
| --- | --- | --- |
| 처리한 사건 기록 (Inbox 역할) | `processed_order_confirmation.event_id` 유일 제약 | 같은 사건의 재전달 |
| 업무 키 | 같은 테이블의 `order_id` 유일 제약, 구매 활동 `(order_id, product_id)` 유일 제약 | 같은 주문을 다른 eventId로 보낸 경우 |
| 내용 대조 (그 위에 한 겹) | `fact_snapshot` 비교 | 같은 ID인데 수량·시각·품목이 다른 메시지를 정상 중복으로 숨기는 것. 충돌로 드러내 DLT로 보낸다 |

정상 흐름에서는 같은 eventId의 내용이 다를 수 없다(결정적 eventId, 변경 불가 payload, 문자열 그대로 전송). 내용 대조는 원본이 바뀐 뒤 다시 계산하거나 payload를 직접 고친 경우처럼 이 보장이 깨졌을 때를 드러낸다. 어느 값이 맞는지는 consumer가 판단하지 않는다. 충돌 뒤 정정하는 기능은 없다.

| 관찰 | 행 | 회차 |
| --- | --- | --- |
| broker 확인 + 상태 저장 성공 | PUBLISHED, `publishedAt` 저장, `lastError` 제거 | 다음 행 진행 |
| 전송 실패·대기 초과 | PENDING, 다음 시각·`lastError` 저장 | 중단 |
| broker 확인 뒤 상태 저장 실패·중단 | PENDING 유지, 같은 사건 재발행 가능 | 예외로 종료 |

`lastError`에는 eventId와 가장 안쪽 원인(예외 이름과 메시지)을 남긴다. future가 실패한 경우와 `send()` 호출에서 바로 던진 경우(크기 초과, metadata 대기 초과 등)가 같다. 실패한 회차는 거기서 멈추므로, 재시도 대기가 회차 간격보다 짧은 동안에는 앞쪽의 영구 실패 행 하나가 뒤 행의 전송을 회차마다 늦춘다([실험 4](outbox-experiments.md#실험-4-kafka-흐름의-장애-상황) S5).

`attemptCount`는 send 전에 저장하므로 실제 broker 수신 수가 아니다. 재시도 간격은 2초부터 2배씩 늘어 최대 60초이며, 반복 실패한 행도 PENDING으로 남는다.

## 책임과 설정

- `OrderPaymentTransactions`는 domain의 `OrderOutboxRecorder`만 호출한다. 직렬화·저장은 infrastructure의 `JsonOrderOutboxRecorder`가 담당하며 `record()`는 호출한 TX에 참여한다.
- `OrderOutboxRelay`가 한 회차를 직렬로 실행하고, `OrderOutboxTransactions`가 짧은 DB TX를 맡는다. 전송 대기 중에는 TX를 열어 두지 않는다.
- `KafkaOrderConfirmedPublisher`는 저장된 payload 문자열을 해석하지 않고 그대로 보내고 future 완료까지 기다린다. commerce-api의 `application.yml`이 producer 값 직렬화를 `StringSerializer`로 덮어쓴다. 공통 `kafka.yml`은 변경하지 않았다. import한 `kafka.yml`이 첫 문서보다 우선하므로 덮어쓰는 값은 뒤 문서에 있다.
- relay는 payload 내용을 검사하지 않는다. 읽을 수 없는 payload는 PUBLISHED가 되고, consumer가 형식 오류로 DLT에 원본 그대로 격리한 뒤 다음 record로 진행한다(`OutboxUnreadablePayloadE2ETest`).
- 메시지 모양은 공유 예시 `modules/kafka/src/testFixtures/resources/contracts/order-confirmed-v1.json`으로 맞춘다. producer 계약 테스트는 저장 payload가 이 예시와 같은 JSON인지, consumer 계약 테스트는 parser가 이 예시를 읽는지 확인한다. IDL·Schema Registry는 쓰지 않는다.
- Outbox 기록은 `commerce.order-events.enabled`와 무관하게 수행한다. 이 설정은 publisher·relay 등록을 제어한다.
- `OrderOutboxRelayScheduler`는 `commerce.order-events.enabled=true`와 `commerce.order-events.relay.scheduling-enabled=true`가 모두 켜졌을 때만 등록된다. 전달만 꺼도 relay 없이 앱이 기동하고 Outbox 기록은 계속 남는다. `scheduling-enabled`의 기본은 false, local만 true다. 인스턴스 하나에서만 켜야 하며 여러 프로세스의 동시 실행을 막는 장치는 없다.

| `commerce.order-events.relay` | 값 |
| --- | --- |
| `fixed-delay-ms` | 10000 |
| `batch-size` | 200 |
| `send-wait` | 130s |
| `first-retry`·`max-retry` | 2s·60s |

- 스케줄러는 기본 단일 스레드라, 응답 없는 전송을 기다리는 동안 다른 `@Scheduled` 작업이 밀릴 수 있다.
- PENDING 조회는 SQL로 확인한다: `SELECT COUNT(*), MIN(created_at) FROM order_outbox WHERE status = 'PENDING';`
- `benchmark` 프로필은 `ddl-auto: validate`라서, 기존 측정 DB에는 기동 전에 `order_outbox` 테이블이 필요하다. 제품 설정은 바꾸지 않았다.
- 조회·재개·재처리 절차와 보존은 [운영 절차](operations.md)와 [ADR 0066](../adr/0066-keep-outbox-and-dedup-records-without-automatic-deletion.md)을 따른다. 두 테이블 모두 자동 삭제하지 않는다.

## 기존 누락 복구

relay는 Outbox 행만 찾으므로 도입 전에 빠진 주문은 `OrderOutboxRecovery`로 행을 보충한다. 시작 인자 `commerce.order-events.recovery.order-ids`로 대상을 명시할 때만 `OrderOutboxRecoveryRunner`가 등록된다.

| 대상 상태 | 결과 |
| --- | --- |
| 확정 주문, 행 없음 | 저장된 eventId·confirmedAt·품목으로 PENDING 추가, relay가 전송 |
| 같은 내용의 행 있음 | 변경 없음 |
| 다른 내용의 행 있음 | 예외, 행 유지 |
| 미확정이거나 확정 시각 없음 | 예외, 앱 시작 중단 |

복구는 PG를 호출하지 않고 주문·예약·재고를 바꾸지 않는다.

## 검증 구성

`OutboxDeliveryE2ETest`는 실제 `commerce-streamer`를 별도 JVM으로 띄워 주문 API부터 구매 활동까지 확인한다. streamer classpath는 `commerce-api`의 `test`·`experimentTest` 태스크에만 전달하며 제품 runtime 의존성은 없다. `W6OutboxRecoveryExperimentTest`는 W6 실행 jar와 현재 앱, streamer를 같은 격리 DB에 연결하며 `check`에 포함되지 않는다. consumer의 중복 처리와 구매 활동 저장 코드는 W6 그대로다.
