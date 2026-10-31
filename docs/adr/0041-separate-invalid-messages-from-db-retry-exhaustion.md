# ADR 0041: 메시지 오류는 DLQ로 분리하고 DB 재시도 소진 시 소비를 중단한다

Status: Accepted — 정책 채택, 업무용 error handler 연결은 ADR 0049
Related: [ADR 0040](0040-prepare-order-event-receiving-without-auto-subscription.md), [ADR 0039](0039-record-order-confirmation-and-purchases-atomically.md), [week6 설계](../week6/design.md#consumer-failure-policy)

Verification: 후속 격리 검증은 [실험 문서](../week6/kafka-settings-experiments.md#dlq-delivery-experiment)와 [최신 구현 기록](../week6/implementation-log.md#selected-settings-verification)에 둔다. 아래 본문은 결정 당시 기록으로 보존한다.
Follow-up: DB 저장 재시도 횟수는 [ADR 0042](0042-use-two-db-retries-before-stopping-consumer.md)에서 최대 2회로 구체화한다. 이 ADR의 오류 분기 결정은 Accepted를 유지하며 아래 본문은 횟수·간격을 정하기 전의 맥락을 보존한다.

Follow-up (ack): 당시 [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md)에서 MANUAL_IMMEDIATE를 선택했고, 현재는 [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)의 BATCH로 대체했다. 이 ADR의 기존 결정은 Accepted를 유지하며 아래 본문은 결정 당시 기록으로 보존한다.

Follow-up (conflict): [ADR 0047](0047-quarantine-conflicting-order-confirmation-facts.md)에서 같은 식별자의 다른 사실을 DLQ로 분리한다. 기존 DB 보호·오류 분기 결정은 Accepted를 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (DLQ failure): [ADR 0048](0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md)에서 DLQ 전송 실패 시 중단·사람의 복구 후 같은 group 재시작을 선택한다. 기존 결정은 Accepted로 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (error handler): [ADR 0049](0049-connect-order-consumer-error-handler.md)에서 재시도 분류·중단 범위·DLQ topic을 정하고 업무용 오류 처리기를 연결한다. 기존 결정은 Accepted를 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (retry): [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)에서 메시지 문제가 아닌 실패의 재시도를 1초·2초에서 "1초부터 2배, 한 간격 최대 30초, 합 5분"으로 바꾼다. 이 결정은 Accepted를 유지한다.

## Context

구매 활동 수신 parser·DB handler와 자동 구독 없는 준비 상태를 구현했다. 격리 Kafka 실험에서 제한 재시도 뒤 기본 recoverer가 실패 주문을 건너뛰는 경우와, 즉시 중단해 offset을 유지하고 복구 후 다시 읽는 경우를 비교했다.

사용자는 메시지 자체 오류를 DLQ로 분리하고 일시적인 DB 오류는 제한적으로 재시도하는 방향을 선택했다. DB 재시도를 다 쓴 뒤에도 DLQ로 보내면 DB 장애 때문에 정상 주문까지 격리할 수 있으므로 소진 후 처리를 구분해야 한다. 여러 번의 DB 연결 실패만으로 메시지 자체가 잘못됐다고 판단하지 않는다.

## Decision

- 메시지 자체의 형식·필수 값 오류는 DLQ로 분리하는 방향을 채택한다. 예를 들어 현재 계약의 orderId 누락이나 잘못된 구매 수량을 들 수 있다. 현재 메시지에는 userId 필드가 없다.
- DLQ 전송 성공을 확인하기 전에 원본 record의 offset을 진행시키지 않는다. DLQ 전달은 구매 활동 DB 반영 완료와 구분하며, DLQ로 보냈다는 이유로 업무 처리 완료 기록을 남기지 않는다.
- 재시도 대상으로 분류한 일시적인 DB 오류는 제한된 횟수로 재시도한다. **재시도를 소진해도 DB 오류가 계속되면 소비를 중단하고 원인을 드러낸다.** 실패 record를 지나가도록 offset을 commit하거나 DB 재시도 소진만을 이유로 DLQ로 보내지 않는다.
- 원인을 해결한 뒤 같은 group으로 재시작해 저장된 위치부터 다시 처리한다. DB commit 또는 검증된 동일 사실 no-op 뒤에 offset을 진행하는 원칙과 ADR 0039의 DB 원자성·멱등성은 유지한다.
- 재시도 횟수·간격, 상세 예외 분류, 중단 범위, 자동/수동 재시작 주체는 아직 선택하지 않았다. DLQ topic 구성·전송 실패 처리·관찰과 재처리 경로도 구현 전에 구체화한다. 기존 실험의 2회·200ms를 업무용 값으로 채택하지 않는다.
- 이 결정은 오류 처리 정책의 채택이다. 업무용 listener·factory·DLQ 코드를 등록하거나 기존 공통 factory를 바꾸는 구현은 포함하지 않는다. 기본 자동 구독 없는 상태는 유지한다.

## Options considered

| 방식 | 판단 |
| --- | --- |
| DB 재시도 소진 뒤 기본 로그·skip | 구매 활동이 저장되지 않은 주문을 지나갈 수 있어 채택하지 않음 |
| 원인 구분 없이 재시도 소진 메시지를 모두 DLQ로 이동 | DB 장애 중 정상 주문도 대량 격리될 수 있어 채택하지 않음 |
| 메시지 오류는 DLQ, DB 재시도 소진은 소비 중단 — 채택 | 메시지의 문제와 저장소의 문제를 구분하고 DB 복구 후 원본 record를 다시 처리 |

## Consequences

일시적인 DB 오류의 자동 회복을 시도하면서, 회복하지 못한 주문은 Kafka의 저장 위치를 유지해 복구 대상으로 남긴다. 중단 범위에 속한 뒤의 정상 메시지도 기다릴 수 있으므로 원인 노출과 재시작 경로가 필요하다.

메시지 자체 오류의 DLQ 경로는 별도 구현·검증이 필요하다. 같은 식별자의 다른 사실 등 계약 충돌과 구체적인 DB 예외의 분류도 구현 전에 확정한다. 이 문서가 모든 예외를 이미 분류했다는 뜻은 아니다.

현재 수신 adapter는 예외를 호출자에게 전달하고 업무용 Kafka 구독은 없다. 실제 연결 시 재시도 소진·DLQ 발행 결과·실제 committed offset·복구 후 재시작까지 선택한 조합으로 검증해야 한다.

## Evidence

- [실패 처리 비교 실험](../week6/kafka-settings-experiments.md#failure-handling-experiment): 재시도 후 기본 후처리와 즉시 중단을 각각 비교했다. **제한 재시도 후 중단 조합이나 DLQ 경로를 검증한 실험은 아니다.**
- [DB commit 뒤 재전달 실험](../week6/kafka-settings-experiments.md#db-commit-replay-experiment): 기존 DB 효과를 유지한 채 같은 group에서 다시 받고 동일 사실 no-op 뒤 offset이 진행하는 것을 확인했다.
- [정책 채택과 남은 구현 기록](../week6/implementation-log.md#consumer-failure-policy)
- 이 결정 기록에서는 새 테스트를 실행하지 않았다. 실제 구현과 조합 검증은 세부 설정 선택 이후 진행한다.
