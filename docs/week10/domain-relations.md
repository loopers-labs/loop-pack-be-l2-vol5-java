# Week 10 결제 사고 도메인 관계

## 애그리거트와 외부 경계

```mermaid
classDiagram
    class Order {
        buyerId
        status
        confirmedAt
        stockReservationStatus
    }
    class Payment {
        paymentKey
        paymentAttemptKey
        providerOrderId
        amount
        status
        transactionKey
    }
    class Stock {
        productId
        availableQuantity
    }
    class PaymentGateway {
        <<port>>
        approve(request)
        findByPaymentKey(paymentKey)
    }
    class LocalPgFixture {
        <<test adapter>>
        createAttempts
        transactions
        responseDropControl
    }
    class OrderConfirmedOutbox {
        eventId
        status
    }
    class PurchaseActivity {
        orderId
        productId
        quantity
        confirmedAt
    }
    class WeeklyRankingSnapshot {
        snapshotId
        periodStart
        score
        rank
    }

    Order "1" *-- "0..1" Payment : owns
    Order "1" --> "1..*" Stock : reserves
    Order ..> PaymentGateway : approve / reconcile
    PaymentGateway <|.. LocalPgFixture
    Order "1" --> "0..1" OrderConfirmedOutbox : confirms once
    OrderConfirmedOutbox --> PurchaseActivity : Kafka event
    PurchaseActivity --> WeeklyRankingSnapshot : fixed weekly input
```

## 결제 상태와 복구 경계

```mermaid
sequenceDiagram
    participant T as 사고 테스트
    participant A as Commerce API
    participant DB as MySQL
    participant PG as Local PG fixture
    participant O as Outbox/Kafka
    participant S as Streamer
    participant B as Weekly batch/API

    T->>A: 실제 주문 생성 및 결제 확정
    A->>DB: PENDING + 재고 RESERVED 저장
    A->>PG: 기존 주문/금액/attempt로 create
    PG->>PG: SUCCESS 거래를 원장에 기록
    PG--xA: 선택한 세 건의 응답 유실
    A->>DB: UNKNOWN 유지, 예약 유지
    T->>PG: 읽기 전용 권위 조회로 증거 수집
    T->>A: 본인 주문 reconcile API
    A->>PG: 저장된 payment key로 기존 거래 조회
    PG-->>A: 동일 거래 SUCCESS
    A->>DB: 계약 검증 후 주문 확정·예약 소비·Outbox 기록
    A->>O: Outbox relay가 이벤트 발행
    O->>S: OrderConfirmed event
    S->>DB: 구매 활동을 주문/상품 단위로 저장
    T->>B: 새 snapshot으로 주간 job 실행
    B-->>T: 주간 랭킹 API 결과
```

## 불변 관계

- `Order`는 구매자와 결제 시도 한 건을 소유한다. 재조정은 요청자의 소유 주문만 조회한다.
- `Payment`의 `paymentKey`, `paymentAttemptKey`, `providerOrderId`, 저장 금액은 PG 응답의 동일 거래와 대사한다.
- PG SUCCESS 응답이 유실되면 PG 거래는 SUCCESS일 수 있고 내부 Payment는 UNKNOWN일 수 있다. 이는 서로 다른 저장 경계의 사실이다.
- `PENDING`과 `UNKNOWN` 주문은 성공 반영 전까지 재고 예약을 유지한다. 승인 성공 적용은 예약을 한 번 소비하며 재고를 다시 차감하지 않는다.
- 성공 적용과 주문별 Outbox 기록은 commerce DB 트랜잭션에서 함께 처리한다. 같은 주문의 반복 대사는 추가 Outbox 사실을 만들지 않는다.
- 주간 점수의 기간은 PG 요청 시각이나 복구 시각이 아니라 저장된 `confirmedAt`을 따른다. 기존 공개 snapshot을 직접 수정하지 않고 새 snapshot을 공개한다.
