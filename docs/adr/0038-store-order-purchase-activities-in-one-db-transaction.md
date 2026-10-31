# ADR 0038: 한 주문의 상품별 구매 활동을 하나의 DB 트랜잭션으로 저장한다

Status: Accepted
Related: [ADR 0035](0035-publish-order-confirmation-after-db-commit.md), [week6 설계](../week6/design.md#purchase-activity-storage)

Follow-up: 완료 이벤트 기록·동일 사실 재전달·동시 경쟁의 rollback 후 확인은 [ADR 0039](0039-record-order-confirmation-and-purchases-atomically.md)에서 확장한다. 상품별 유일성과 한 주문의 원자적 저장 결정은 Accepted를 유지한다. 아래 본문은 DB 저장 기초를 선택한 당시의 기록이다.

## Context

주문 확정 메시지 한 건은 주문 하나와 여러 상품의 수량을 담는다. 사용자는 상품별 구매 활동의 중복 기준으로 `(orderId, productId)`를 선택했고, 중간 상품 저장이 실패하면 해당 주문의 저장을 전부 취소하기로 했다.

주문 API의 확정 트랜잭션은 이미 commit된 뒤 메시지를 보낸다. streamer에서 구매 활동을 저장하는 작업에는 별도의 DB 경계가 필요하다. 메시지 한 건을 유지하는 결정과 상품별 활동을 여러 행으로 저장하는 결정은 함께 적용할 수 있다.

## Decision

- 기존 `OrderConfirmed` JSON은 유지한다. 한 메시지의 `items`에 여러 `productId, quantity`가 들어간다. 각 상품의 ID는 어떤 상품의 구매인지 식별하기 위해 보존한다.
- streamer의 `OrderPurchase`는 DB 저장용 불변 입력이며 Kafka wire DTO가 아니다. 주문 ID·최초 확정 시각과 양수 상품 ID·수량을 검증하고, 상품별 수량을 합산해 ID순으로 정리한다.
- `product_purchase_activity`에 상품별 한 행을 저장한다. 각 행은 orderId·productId·quantity·confirmedAt을 보존하고, `(order_id, product_id)`에 DB 유일 제약을 둔다. 현재 상품·주문의 존재를 확인하기 위한 조회나 연관관계·외래 키를 추가하지 않는다.
- application의 `PurchaseActivityRecorder.record()`가 모든 품목 저장을 하나의 DB 트랜잭션으로 묶는다. domain 인터페이스인 `PurchaseActivityRepository`에 의존하고, infrastructure의 JPA 구현이 저장한다.
- 중간 저장에서 런타임 오류나 DB 제약 위반이 발생하면 이번 호출에서 저장한 모든 품목을 rollback한다. 기존에 commit된 다른 기록은 보존한다. 예외를 삼키거나 실패한 트랜잭션에서 정상 commit을 시도하지 않는다.
- 이번 단계는 DB 저장 기초에 한정한다. 중복 insert는 DB가 거절하며 예외가 전파된다. 동일 이벤트의 정상 no-op, eventId·orderId의 완료 기록과 payload 충돌 대조, Kafka listener·ack·offset 연결은 후속 단계다. 현재 recorder를 완성된 멱등 consumer로 간주하지 않는다.

## Options considered

| 방식 | 검토 |
| --- | --- |
| 주문 하나를 JSON 한 행으로만 저장 | 메시지 구조를 그대로 둘 수 있지만 이번 과제의 상품별 구매 활동과 orderId·productId 유일성 보호는 별도 설계가 필요함 |
| 상품별 행을 각자 commit | 중간 상품 실패 시 일부 구매 활동만 남음 |
| 상품별 행을 한 DB 트랜잭션으로 저장 — 채택 | 상품별 결과를 남기면서 한 주문의 전체 저장·rollback을 보장함 |

## Consequences

주문 42의 상품 7×5·상품 8×3은 메시지 1건과 구매 활동 2행으로 표현한다. 다른 주문의 같은 상품 구매도 저장할 수 있다. 주문 확정과 구매 활동 저장은 각각 독립된 DB 작업이며, 이 결정만으로 DB와 Kafka 사이의 원자성이나 offset 재전달 멱등성을 보장하지 않는다.

완료 이벤트 기록을 추가할 때에는 모든 품목과 같은 트랜잭션에 묶어야 한다. DB 유일 제약을 통한 경쟁 보호는 유지하고, 실패한 트랜잭션 밖에서 동일 사실인지 확인하는 경계를 후속 설계에서 정한다.

## Evidence

- [구매 사실 입력](../../apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/OrderPurchase.java)
- [상품별 구매 활동](../../apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProductPurchaseActivity.java)
- [트랜잭션 경계](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityRecorder.java)
- [실제 MySQL 검증](../../apps/commerce-streamer/src/test/java/com/loopers/application/purchase/PurchaseActivityRecorderTest.java)
- 현재 구현·실행 결과는 [week6 구현 기록](../week6/implementation-log.md#purchase-activity-storage)을 따른다.
