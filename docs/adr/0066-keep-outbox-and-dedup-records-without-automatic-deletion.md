# ADR 0066: Outbox와 소비 완료 기록을 자동 삭제하지 않는다

Status: Accepted
Related: [ADR 0064](0064-deliver-order-events-with-single-polling-relay.md), [ADR 0065](0065-recover-pre-outbox-orders-from-stored-facts.md), [운영 절차](../week7/operations.md)

## Context

Outbox는 재발행의 근거이고 소비 완료 기록은 중복 효과를 막는 근거다. Kafka record는 보존 기간이 지나면 사라진다. 세 데이터의 보존이 어긋나면 다시 보낼 수 있는 사건의 중복 방지 기록만 사라져 구매 활동이 늘어날 수 있다.

반복 실패를 분리하는 `BLOCKED` 상태를 둘지도 함께 정해야 한다.

## Decision

- 이번 범위에서 `order_outbox`와 `processed_order_confirmation`에 자동 삭제를 두지 않는다. 미발행 행은 어떤 경우에도 정리하지 않는다.
- 나중에 정리를 도입한다면 PUBLISHED Outbox를 먼저 지우고, 소비 완료 기록은 그 사건이 재발행될 수 없게 된 뒤에 지운다. 복구는 주문에서 같은 eventId를 다시 만들 수 있으므로 복구 대상이 될 수 있는 주문의 소비 완료 기록은 지우지 않는다.
- `BLOCKED` 상태를 두지 않는다. 반복 실패한 행은 PENDING으로 남아 계속 시도하고(다음 시도 시각까지의 대기 최대 60초, 실제 시도에는 회차 간격이 더해진다), `last_error`와 조회문으로 찾는다.
- 재개는 relay 자동 재시도로 처리한다. PUBLISHED 행의 재처리는 대상 eventId의 상태를 되돌리는 로컬 절차로 처리하며 전용 도구를 두지 않는다.

## Alternatives and costs

| 대안 | 판단 |
| --- | --- |
| PUBLISHED 즉시 삭제 | 전달 이력과 재처리 근거를 잃는다. 채택하지 않음 |
| 소비 완료 기록에 짧은 보존 기간 적용 | 복구·재처리된 사건이 새 효과를 만들 수 있다. 채택하지 않음 |
| `BLOCKED` 상태 분리 | 정상 행 진행은 이미 보장되므로 상태·재개 경로를 추가할 이유가 작다. 영구 오류 빈도를 측정하지 않았다 |
| 대상 eventId 재개 도구 | 일시 장애는 자동 재개되고 재처리는 빈도가 낮다고 가정해 만들지 않음 |

비용은 두 테이블이 계속 커지고 PENDING 조회 비용이 늘 수 있다는 점이다. 증가량과 조회 비용은 측정하지 않았다. 영구 오류 행은 재시도를 계속 쓴다. 재처리 UPDATE는 사람이 수행하므로 대상을 잘못 지정할 수 있으나, 같은 사건의 재발행이라 업무 효과는 consumer가 막는다.

## 검증

- `OrderOutboxRelayTest`가 backlog 조회문과 PUBLISHED 행 재처리 UPDATE를 실제 MySQL에서 실행하고 같은 payload가 새 offset에 기록됨을 확인한다.
- 재처리 record의 consumer 처리는 이 테스트 범위가 아니다. 같은 사건 재발행의 consumer no-op은 `OutboxDeliveryE2ETest`에서 확인했다.
- 7일 보존 경과는 시험하지 않았다.
