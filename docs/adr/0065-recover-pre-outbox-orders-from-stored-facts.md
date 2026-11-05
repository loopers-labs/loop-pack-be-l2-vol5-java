# ADR 0065: Outbox 도입 전 확정 주문을 저장된 사실로 보충한다

Status: Accepted
Related: [ADR 0036](0036-derive-order-confirmation-event-id-from-order-id.md), [ADR 0063](0063-record-order-confirmation-outbox-in-tx-c.md), [ADR 0064](0064-deliver-order-events-with-single-polling-relay.md), [week7 설계](../week7/design.md)

## Context

relay는 Outbox 행만 찾는다. 직접 전송 시절에 확정됐지만 전달되지 않은 주문에는 행이 없어서 Outbox를 배포해도 찾지 못한다. 어떤 주문이 이미 소비됐는지 모를 수 있고, 복구 때문에 결제나 주문 상태가 바뀌면 안 된다.

## Decision

- `OrderOutboxRecovery`가 대상 주문의 저장된 confirmedAt·품목으로 기존과 같은 eventId의 Outbox 행을 없을 때만 추가한다. 전달은 평소처럼 relay가 맡는다.
- 이미 행이 있으면 내용을 비교한다. 같으면 아무것도 하지 않고, 다르면 추측하지 않고 예외를 던진다. 확정 주문이 아니거나 확정 시각이 없으면 거부한다. 따라서 이전 POINT 주문은 대상이 아니다.
- 복구는 PG를 호출하지 않고 주문·예약·재고를 바꾸지 않는다.
- 진입점은 `commerce.order-events.recovery.order-ids`를 시작 인자로 줄 때만 등록되는 `OrderOutboxRecoveryRunner`다. 대상을 명시해야 하며 자동 소급하지 않는다. 공개 API로 노출하지 않는다. 대상이 실패하면 앱 시작을 멈춘다.
- 검증은 `b0d84b4` revision 실행물로 재현한 상태에서 수행한다. 제품에 W6 우회 스위치를 두지 않고, SUCCESS를 직접 넣거나 Outbox 행을 지워 상태를 만들지 않는다.

## Alternatives and costs

| 대안 | 판단 |
| --- | --- |
| 복구용 새 eventId 생성 | consumer가 같은 주문의 다른 사건으로 판단한다. 시험에서 처리가 진행되지 않았다. 채택하지 않음 |
| 확정 주문 전체 자동 스캔 | 대상을 넓게 재발행하고 이전 POINT 주문과 섞인다. 과제 범위 밖으로 채택하지 않음 |
| 공개 관리자 API | 외부 노출 표면이 생긴다. 채택하지 않음 |
| W6 경로를 현재 코드의 테스트 구성으로 재구현 | Outbox 저장을 끄는 장치가 필요하고 실제 W6 실행이 아니다. 채택하지 않음 |

비용은 사람이 대상 주문을 찾아 명시해야 하고, 이미 소비된 주문도 재발행될 수 있다는 점이다. 중복 효과는 consumer가 막는다. 저장된 주문이 틀렸다면 복구도 그 내용을 그대로 보낸다.

## 검증

- `OrderOutboxRecoveryTest`가 동일 identity 추가, 재실행 no-op, 내용 충돌, 미확정 주문 거부를 확인한다.
- `W6OutboxRecoveryExperimentTest`가 W6 앱과 현재 앱, 실제 streamer를 같은 격리 MySQL·Kafka에 연결해 복구를 확인한다. `check`에 포함되지 않으며 `experimentTest`로 실행한다.
