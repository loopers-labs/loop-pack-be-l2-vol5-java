# ADR 0099. 결제 결과를 확인하지 못하고 끝난 조회를 주문 ID와 이유로 기록한다

Status: Accepted
Extends: [ADR 0096](0096-log-pg-boundary-and-failed-result-save.md)
Related: [결제 로그 지도 4절](../week10/log-map.md#4-결과-확인-보류)

## 배경

reconcile·callback·복구 배치는 `OrderFacade.resolve()`에서 PG를 조회한다. 조회 결과가 UNKNOWN이면 TX B를 타지 않고 끝나므로 DB에는 흔적이 남지 않는다. 그중 조회 회로가 열린 경우는 `ResilientPaymentProvider`가 PG에 묻지 않고 `UNKNOWN(CIRCUIT_OPEN)`을 돌려줘, 어댑터의 `PG 요청`·`PG 응답` 로그도 없다. 고객이 결과 확인을 눌렀는데도 주문이 확인 중에 머문 이유를 어느 기록에서도 찾을 수 없었다.

## 결정과 근거

`resolve()`에서 조회 결과가 UNKNOWN이면 이유와 상관없이 WARN 한 줄을 남기고, 처리 결과는 바꾸지 않는다.

```text
결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN
```

- 필드는 우리 주문 ID, 확인을 시작한 경로(`RECONCILE`·`CALLBACK`·`BATCH`), `UnknownCause`다. 주문 ID가 필요하므로 지표가 아니라 로그다. 시스템 전체에 하나인 회로 상태는 별도 지표로 본다.
- 모든 UNKNOWN에 남겨, 회로 열림과 PG에 물어본 timeout·5xx를 같은 주문 ID로 찾는다. 정상 결제에서는 남지 않는다.
- WARN: 실패로 확정한 것이 아니라 결과를 모르는 채 끝난 것이어서 ERROR가 아니다. 주문이 확인 중에 머무는 신호라 INFO보다 높다.

| 대안 | 판단 |
| --- | --- |
| PG에 묻지 않은 경우(회로 열림)만 기록 | 줄은 적지만 timeout·5xx 보류는 PG 주문 번호로 어댑터 로그를 다시 찾아야 한다 |
| 회로 상태만 지표로 기록 | 회로가 열린 시간대는 보이지만, 그 사이에 어느 주문이 확인을 시도했는지 남지 않는다 |
| `ResilientPaymentProvider`에서 기록 | 우리 주문 ID와 확인 경로를 모른다 |

## Consequences

- 장애 중에는 결과 확인 시도마다 한 줄이 남는다. 같은 고객이 여러 번 누르면 그만큼 남는다.
- PG에 물어본 timeout·5xx에서는 조회 재시도마다 남는 어댑터 로그 뒤에 이 줄이 한 번 더 붙는다.

## 검증

`PaymentBoundaryLogTest`

- 생성 응답을 끊어 UNKNOWN을 만든 뒤 조회 회로를 열고 reconcile하자, PG 조회 호출 수가 그대로였고 `PG 요청: op=lookup`은 없었다. `결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN`이 남았다.
- 조회 응답을 0.7초 늦추자(조회 read timeout 0.5초) `PG 응답 없음: op=lookup ... error=SocketTimeoutException` 두 번 뒤에 `결제 결과 확인 보류: orderId=1 source=RECONCILE cause=TEMPORARY_FAILURE`가 남았다.
