# PG 어댑터

WireMock은 토스페이먼츠 형식으로 요청을 받고 응답한다. `TossPaymentAdapter`는 우리 내부 결제 요청과 결과를 토스 규격으로 연결한다. 실제 토스 계정이나 금전 결제는 사용하지 않는다.

```text
우리 결제 요청 → TossPaymentAdapter → 토스 형식 요청 → WireMock
우리 결제 결과 ← TossPaymentAdapter ← 토스 형식 응답 ← WireMock
```


| 구성                 | 책임                            |
| ------------------ | ----------------------------- |
| WireMock           | 토스 형식의 응답과 성공·실패·응답 유실 상황 재현  |
| TossPaymentAdapter | 요청 변환, 외부 응답 검증·변환, 통신 오류 구분  |
| 결제 유즈케이스           | 변환된 결과를 주문·결제·재고에 반영          |
| API 계층             | 처리 결과를 우리 `meta/data` 응답으로 변환 |



| 토스 응답             | 내부 의미                                  |
| ----------------- | -------------------------------------- |
| `status: DONE`    | `SUCCESS`                              |
| `status: ABORTED` | `FAILED`                               |
| `totalAmount`     | 결제 금액                                  |
| `paymentKey`      | 외부 결제 식별자. 거래 식별자 `transactionKey`와 구분 |
