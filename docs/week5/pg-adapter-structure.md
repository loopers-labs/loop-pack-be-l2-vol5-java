```mermaid
flowchart TB
    UseCase["주문 결제 유즈케이스"] --> Gateway["PaymentGateway 인터페이스"]
    UseCase --> Domain["Order · Payment · OrderItem · Stock"]

    subgraph TossPair["토스 테스트 구성"]
        Toss["TossPaymentAdapter"] -->|HTTP| TossMock["WireMock"]
        TossFixture["토스 fixture"] -->|요청·응답 설정| TossMock
    end

    subgraph OtherPair["다른 PG 테스트 구성"]
        Other["다른 PG 어댑터"] -->|HTTP| OtherMock["WireMock"]
        OtherFixture["해당 PG fixture"] -->|요청·응답 설정| OtherMock
    end

    Toss -. 구현 .-> Gateway
    Other -. 구현 .-> Gateway
```
```text
apps/commerce-api/src/
├─ main/java/com/loopers/order/
│  ├─ domain/
│  │  └─ PaymentGateway.java
│  └─ infrastructure/
│     └─ payment/
│        └─ toss/
│           ├─ TossPaymentAdapter.java
│           └─ TossHttpClientFactory.java
└─ test/java/com/loopers/order/
   └─ infrastructure/
      └─ payment/
         └─ toss/
            ├─ TossPgFixture.java
            └─ TossPaymentAdapterTest.java
```
