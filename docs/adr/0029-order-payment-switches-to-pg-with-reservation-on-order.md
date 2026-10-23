# ADR 0029: 주문 결제를 포인트에서 PG로 교체하고 재고 예약 상태를 주문에 둔다

Status: Accepted
Related: [ADR 0007](0007-order-item-keeps-order-time-snapshot.md), [ADR 0012](0012-stock-decrement-locks-product-row.md), [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md), [ADR 0030](0030-pg-payment-split-transactions-and-apply-once.md)

## Context

3주차 주문 확정은 한 DB 트랜잭션에서 재고 차감, 포인트 차감, 2% 적립, CONFIRMED를 처리했다. 5주차 과제는 새 결제를 테스트 PG로 바꾸고, 과거 POINT 주문의 결제 사실은 그대로 조회하게 한다. PG 결과는 나중에 확인되므로, 결과를 기다리는 동안 이 주문 몫의 재고를 잡고 있다는 사실을 DB에 남겨야 한다.

## Decision

- 새 주문 확정은 PG만 사용한다. PG 주문은 포인트를 차감하지 않고 2% 적립도 하지 않는다(과제의 정상 PG 주문 기대값 "포인트 변화 0"). 포인트 충전·조회·만료는 그대로 둔다.
- 주문 결제 입구였던 `OrderConfirmationService`와 `PointBalanceService`의 `use`·`reward`를 지운다. 포인트 원장 모델의 `use`·`reward`는 만료 처리와 과거 원장 재현에 쓰이므로 남긴다.
- Order에 `paymentMethod`(POINT, PG)를 둔다. 결제수단은 확정 시점에 정해지며 DRAFT는 null이다. 기존 포인트 `confirm()`은 POINT를 기록하며, 과거 POINT 주문을 재현하는 데 쓴다.
- 재고 예약 상태는 Order의 `reservationStatus`(RESERVED, CONSUMED, RELEASED) 하나로 둔다. 별도 예약 테이블과 상품별 예약 행은 만들지 않는다. 돌려줄 상품과 수량은 주문 뒤 바뀌지 않는 `OrderItem`에서 읽는다. 실제 가용 재고는 기존처럼 `Product.stock`이 관리한다.
- `status`와 `reservationStatus`는 `startPaymentWithReservedStock()`, `completePayment()`, `failPaymentWithReleasedStock()` 세 메서드에서만 함께 바꾼다. `paidAmount`는 성공 반영 때만 기록한다. PAYMENT_PENDING이 아닌 주문에 결과를 반영하면 CONFLICT 예외다.
- DRAFT와 과거 POINT 주문의 `reservationStatus`는 null이며 `paymentMethod`로 구분한다.
- 주문 조회 응답은 결제 시도의 저장 상태를 함께 보여 준다. 주문 상태 PAYMENT_PENDING만으로는 대기(PENDING)와 미확인(UNKNOWN·REQUESTED)을 구분할 수 없기 때문이다. 고객 응답에는 `paymentStatus` 한 칸만, 관리자 응답에는 `providerOrderId`·`transactionKey`까지 둔다. 결제 시도가 없는 주문은 null이다. 조회는 PG를 부르지 않는다.

## Alternatives and costs

- **별도 예약 테이블(상품별 행)**: 이번 범위에서는 모든 품목이 같이 잡히고, 같이 소비·반환돼 상품마다 상태가 갈라지는 장면이 없다. 수량을 다시 적으면 OrderItem과 같은 숫자를 두 곳에서 맞춰야 한다.
- **예약 상태를 저장하지 않고 주문 상태로 판단**: 현재 범위에서는 가능하고 저장 칸이 하나 적다. 대신 "PAYMENT_PENDING이면 재고를 잡고 있다"는 약속을 문서로만 들고 다녀야 한다. 예약 상태를 저장된 값으로 직접 보이게 하려고 주문에 칸을 두는 쪽을 골랐다.
- **선택한 방식의 대가**: `CONFIRMED + RELEASED`처럼 말이 안 되는 조합이 표현 가능하다. 같은 행이라는 사실만으로 조합이 맞춰지지는 않으므로, 두 칸을 함께 바꾸는 메서드 세 개로만 바꾸고 테스트로 조합을 확인한다.
- **포인트 결제 테스트 정리**: 주문의 포인트 결제 계약이 없어져, 그 계약을 검증하던 테스트(포인트 확정 서비스 단위 테스트, 주문 결제로 포인트를 쓰는 원장 동시성, 포인트 결제·적립 도중 롤백, 만료 포인트로 잔액 부족)를 지웠다. 기대값을 완화한 것이 아니라 계약 자체가 바뀐 것이다. 재고·잠금을 지키던 테스트는 같은 장면을 PG 확정 기준으로 옮겼다.
- **운영 DB 이관**: local·test는 `ddl-auto: create`라 이관이 없다. 옛 데이터가 있는 DB라면 기존 CONFIRMED 주문의 `paymentMethod`를 POINT로 채우는 백필이 필요하다. 4주차 benchmark DB는 갱신하지 않아 그 프로필로는 앱이 시작하지 않는다.

## 다시 볼 조건

실패 후 재결제 대기, 부분 취소·환불, 상품별 처리처럼 주문 상태와 재고 처리가 갈라지거나 상품마다 처리 결과가 달라지는 기능을 넣을 때, 예약 모델(주문의 칸 하나, 상품별 예약, 별도 테이블)을 다시 비교한다.

## 검증

[week5 구현 기록](../week5/implementation-log.md)에 둔다. `OrderModelTest`(전이 메서드와 조합), `OrderPaymentIntegrationTest`(PG 주문의 포인트 변화 0, 과거 POINT 주문의 PG 시작 거절), 옮긴 재고·잠금 테스트.
