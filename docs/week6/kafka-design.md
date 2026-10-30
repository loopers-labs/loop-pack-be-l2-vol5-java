# 주문 이벤트 흐름 초안

## 공통 이벤트 포맷


| 필드          | 의미                           |
| ----------- | ---------------------------- |
| `eventId`   | 이벤트를 식별하는 고유 ID              |
| `orderId`   | 이벤트가 속한 주문 ID. Kafka key로 사용 |
| `eventType` | 이벤트 종류                       |




## V1 이벤트

V1은 주문 흐름을 표현하는 데 필요한 업무 사실만 이벤트로 남긴다.

| 순서 | 이벤트 | 발행 서비스 | 의미 |
| ---: | --- | --- | --- |
| 1 | `InventoryReserved` | Inventory | 주문 상품의 재고 예약이 완료됐다. |
| 2 | `PaymentCompleted` | Payment | 결제가 완료됐다. |
| 3 | `OrderConfirmed` | Order | 주문이 확정됐다. |
| 4 | `ShippingRequested` | Shipping | 배송 서비스가 배송 건을 만들고 배송사에 배송을 요청했다. 배송사의 물건 인수를 뜻하지는 않는다. |
| 5 | `ShipmentStarted` | Shipping | 배송사가 물건을 실제로 인수했다. 배송 요청 접수와 구분되는 사실이다. |
| 6 | `OrderCancelled` | Order | 주문 취소가 완료됐다. |
| 7 | `PaymentCancelled` | Payment | 결제 취소가 완료됐다. |
| 8 | `InventoryReleased` | Inventory | 예약된 재고가 해제됐다. |




# 고민 포인트

- 이벤트별 payload에 어떤 업무 사실을 담을지, 각 consumer가 실제로 필요로 하는 데이터에 맞춰 정한다.
- `ShippingRequested`는 배송 요청 사실, `ShipmentStarted`는 배송사의 실제 인수 사실을 나타낸다. 배송사 API 접수만으로 실제 인수 사실을 표현하지 않는다.
- 공통 필드는 현재 `eventId`, `orderId`, `eventType`으로 정한다. 추가 필드와 이벤트 간 인과관계 표현은 후속 설계에서 결정한다.
