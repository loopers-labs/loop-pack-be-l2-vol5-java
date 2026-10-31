# ADR 0049: 주문 소비 오류 처리기의 재시도 분류·중단 범위·DLQ topic을 정한다

Status: Accepted — 업무용 error handler 연결·격리 검증, 로컬 활성화는 ADR 0050
Related: [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [ADR 0042](0042-use-two-db-retries-before-stopping-consumer.md), [ADR 0043](0043-increase-db-retry-delays-from-one-to-two-seconds.md), [ADR 0047](0047-quarantine-conflicting-order-confirmation-facts.md), [ADR 0048](0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md), [week6 설계](../week6/design.md#consumer-error-handler)

Follow-up (DB timeout): [ADR 0052](0052-set-mysql-driver-timeouts-per-app.md)에서 MySQL 드라이버 timeout을 둬, 응답 없는 DB에서도 예외가 나 이 오류 처리기의 재시도·중단이 동작하게 한다. 이 결정은 Accepted를 유지한다.

Follow-up (retry): [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)에서 메시지 문제가 아닌 실패의 재시도를 1초·2초에서 "1초부터 2배, 한 간격 최대 30초, 합 5분"으로 바꾼다. 이 결정은 Accepted를 유지한다.

Implementation verification note: [DLT 지연 실험](../week6/kafka-settings-experiments.md#dlt-timeout-experiment)에서 아래 본문의 "최대 10초"와 실제 구현의 차이를 확인했다. setWaitForSendResultTimeout은 최소 대기이며 현재 결과 대기 예산은 125초다. 주문용 poll 제한도 공유 batch의 2분이 아닌 기본 5분이다. 기존 결정 본문은 보존하며, 시간 기준 변경은 선택 대기로 둔다. 현재 동작은 [설계](../week6/design.md#consumer-error-handler)를 따른다.

Follow-up (BATCH): [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)에서 주문용 ack를 BATCH로 바꾸고, DLT 성공 뒤 container가 위치를 기록하도록 ackAfterHandle=true·commitRecovered=false로 연결한다. 재시도·DLT 분류·실패 시 중단 결정은 Accepted로 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

ADR 0041·0047·0048은 메시지 오류·사실 충돌을 DLQ로 분리하고, DB 오류는 ADR 0042·0043의 1초·2초 두 번 재시도 뒤 소비를 중단하며, DLQ 전송이 실패하면 중단하는 방향을 정했다. 실제 오류 처리기를 만들려면 세 가지가 남아 있었다.

- 어떤 예외를 DB 재시도 대상으로 볼지
- 중단할 때 주문 listener의 어느 범위를 멈출지
- DLQ topic 이름과 partition 배정

이 값이 없으면 주문용 listener를 켤 수 없었다. 전용 오류 처리기 bean이 없으면 시작이 실패하도록 해 두었다.

## Decision

- **재시도 분류:** 메시지 오류(`InvalidOrderConfirmedMessageException`)와 사실 충돌(`ConflictingOrderConfirmationException`)만 재시도 없이 DLQ로 보낸다. **그 밖의 모든 예외**는 1초·2초 대기로 두 번 더 시도하고, 그래도 실패하면 중단한다. 모르는 오류도 건너뛰지 않고 중단으로 드러난다.
- **중단 범위:** 주문 listener(`order-confirmed-purchase-activity`) 전체를 멈춘다. concurrency가 여러 개여도 모든 consumer 스레드가 멈춘다. 사람이 원인을 해결하고 같은 group으로 다시 시작한다.
- **DLQ topic:** 원본 topic 이름에 `-dlt`를 붙인다(`order-events` → `order-events-dlt`). partition은 원본 번호를 따르지 않고 원본 key(orderId)로 정한다. 원본 topic·partition·offset·group과 예외 정보는 Spring의 DLT header로 남는다.
- 메시지 오류는 수신 계층이 파싱 실패를 `InvalidOrderConfirmedMessageException`으로 감싸 구분한다. 사실 충돌은 application이 domain의 충돌 예외를 `ConflictingOrderConfirmationException`으로 감싸 수신 계층에 드러낸다. 수신 계층이 domain에 직접 의존하지 않는 기존 계층 규칙을 지킨다.
- DLQ 전송 결과는 최대 10초 기다린다. 기다리는 동안 consumer 스레드가 멈춰 있으므로 `max.poll.interval.ms`(2분)보다 충분히 짧게 둔다.
- 기본 비활성(`commerce.order-events.consumer.enabled=false`)은 유지한다. 로컬 활성화와 topic 생성은 이 결정에 포함하지 않는다.

## Options considered

| 항목 | 선택하지 않은 안 | 선택하지 않은 이유 |
| --- | --- | --- |
| 재시도 분류 | 일시적 DB 오류(`TransientDataAccessException` 계열)만 재시도하고 나머지는 바로 중단 | 분류 목록을 관리해야 하고, 결과는 어차피 중단이라 얻는 이점이 작다 |
| 중단 범위 | 실패한 consumer 스레드만 중단 | DB·DLQ 장애는 보통 모든 partition에 함께 영향을 준다. 부분 중단은 관찰과 재시작이 더 복잡하다 |
| DLQ partition | 원본과 같은 partition 번호 (Spring 기본) | DLQ의 partition 수가 원본 이상이어야 해서 두 topic의 구성이 묶인다 |

## Consequences

- 주문용 listener를 켤 수 있는 조건(전용 오류 처리기)이 갖춰졌다. 실제로 켜려면 `order-events`·`order-events-dlt` topic 생성과 앱별 concurrency 설정이 필요하다.
- 처음 보는 오류도 재시도 3초 뒤 listener 전체를 멈춘다. 처리가 지연될 수 있지만 실패 record를 조용히 건너뛰지 않는다.
- DLQ 전송 성공과 원본 offset commit은 원자적이지 않다. 그 사이에 멈추면 같은 원본이 DLQ에 두 번 들어갈 수 있다.
- 멈춘 뒤 자동으로 다시 시작하지 않는다. 재시작 방법(배포 재시작 또는 운영 도구)은 별도로 정한다.

## Evidence and limits

- [구현 기록](../week6/implementation-log.md#consumer-error-handler): 실제 Kafka·MySQL과 실제 `@KafkaListener`·주문용 factory·이 오류 처리기로 세 경로를 차례로 확인했다.
  - 메시지 오류와 사실 충돌은 DLQ로 보내고(원본 offset 1·2) 뒤의 정상 주문을 처리했다. 기존 주문 42의 수량은 바뀌지 않았다.
  - DB 오류는 세 번 시도(간격 1초 이상·2초 이상) 뒤 멈추고 committed offset을 지켰다. 원인을 없애고 다시 시작하자 이어서 처리했다.
  - DLQ 전송 실패(크기 제한 거절)는 멈추고 원본 offset을 지켰다. 원인을 없애고 다시 시작하자 같은 원본을 DLQ로 보내고 이어서 처리했다.
- 테스트는 partition 1·RF1·concurrency 1이다. concurrency 3에서 listener 전체가 멈추는 것은 코드상 parent container를 멈추는 방식이며 실행으로 확인하지 않았다.
- 실제 DB 서버 중단·broker 장애·DLQ 전송 확인 뒤 commit 전 강제 종료는 재현하지 않았다.
