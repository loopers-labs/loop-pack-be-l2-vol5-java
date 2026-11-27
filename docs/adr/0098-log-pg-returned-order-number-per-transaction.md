# ADR 0098. PG가 돌려준 거래별 주문 번호를 결과 로그에 기록한다

Status: Accepted
Extends: [ADR 0096](0096-log-pg-boundary-and-failed-result-save.md), [ADR 0097](0097-log-pg-response-http-status.md)
Related: [Week 10 설계](../week10/design.md#정상-조회-결과를-한-줄로-남기는-이유)

## 배경

결과 로그의 거래 목록은 거래 키·상태·금액만 남겼다. 로그 앞의 `providerOrderId`는 우리가 요청에 쓴 값이므로, PG가 다른 주문의 거래를 돌려줘도 이 줄만으로는 알 수 없었다.

주문 번호가 다르면 TX B가 대조해 확정하지 않고 `ORDER_MISMATCH` 충돌 이력을 남긴다. 그러나 TX B가 rollback되면 그 이력도 함께 사라진다. 그 순간 PG가 돌려준 값은 외부 API를 다시 불러야 얻을 수 있고, 다시 불러도 현재 값만 돌아온다. PG가 준 금액을 로그에 남긴 근거([ADR 0096](0096-log-pg-boundary-and-failed-result-save.md))와 같은 조건이다.

## 결정과 근거

결과 로그의 거래마다 PG 응답 본문의 주문 번호를 넣는다. 형식은 `거래 키:PG가 준 주문 번호:상태:금액`이다.

```text
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=25
```

- 주문 번호 불일치와 금액 불일치는 모두 복구를 멈추는 조건이다. 금액만 남기면 PG가 준 거래가 요청한 주문의 것인지 로그에서 대조할 수 없다.
- 로그 줄 수는 늘지 않는다. 이미 받은 응답 값이라 추가 PG 호출이 없고, 우리가 만든 번호라 민감정보가 아니다.
- 정상 호출에서는 대개 요청값과 같은 번호를 거래마다 한 번 더 남긴다. 이번 사고 재현의 fake PG는 항상 요청한 주문의 거래를 돌려주므로, 이 필드의 차이는 불일치 응답 계약 테스트로 확인한다.

| 대안 | 판단 |
| --- | --- |
| 현재 필드 유지 | TX B rollback 뒤에는 PG가 돌려준 주문 번호가 어디에도 남지 않는다 |
| 불일치일 때만 주문 번호 기록 | 어댑터가 대조 책임을 일부 갖게 된다. 대조는 TX B가 맡는다 |

## 검증

`PaymentProviderContractTest.mismatchedCreateResponseIsPassedOnAsObservedForResultCheck`에서 `LP-ORD-0000000042`로 생성을 요청하고 PG가 `OTHER-ORDER` 거래를 돌려주자, 로그에 `providerOrderId=LP-ORD-0000000042 ... transactions=[T-1:OTHER-ORDER:PENDING:8000]`이 남았다. 정상 생성·조회 계약 테스트와 `PaymentBoundaryLogTest`의 TX B rollback 테스트에서는 요청과 같은 주문 번호가 거래 목록에 남는다. [구현 기록](../week10/implementation-log.md#a-pg가-돌려준-거래별-주문-번호)
