# 주문 취소와 출고 흐름

```text
OrderConfirmed
  → 배송 서비스가 배송 건을 REQUESTED로 기록

사용자 취소 요청
  → 주문 서비스: CONFIRMED → CANCEL_REQUESTED
  → OrderCancellationRequested 발행
  → 사용자에게 취소 요청 접수 상태 응답

배송 서비스가 자기 DB 상태로 판정
  ├─ REQUESTED → CANCELLED
  │    → 출고 중지, ShippingCancellationAccepted 발행
  └─ STARTED
       → ShippingCancellationRejected 발행 또는 회수 절차

취소 수락 후 재고 해제·결제 취소 결과 수신
  → 주문 서비스가 최종 OrderCancelled 확정
```

- 주문 서비스는 배송 DB를 직접 조회하지 않는다. 배송 서비스가 자기 상태를 판정해 결과 이벤트를 보낸다.
- 배송 서비스는 `REQUESTED → CANCELLED`와 `REQUESTED → STARTED` 전이 중 하나만 성공시킨다.
- 출고 시작은 `ShipmentStarted`, 고객에게 배송 완료는 `ShipmentCompleted`로 구분한다.
- 취소 요청 이벤트는 이미 발행된 이벤트를 회수하지 않는다. 주문은 취소 결과가 모일 때까지 `CANCEL_REQUESTED`로 둔다.
