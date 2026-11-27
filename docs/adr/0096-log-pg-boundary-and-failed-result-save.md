# ADR 0096: PG 요청·결과를 경계에서 한 줄씩 남기고, 결과 저장 TX 실패를 로그로 남긴다

Status: Accepted
Follow-up: PG 응답(5xx·생성 422·정상 생성 202·정상 조회 200·해석할 수 없는 조회 본문)의 HTTP 상태 기록은 [ADR 0097](0097-log-pg-response-http-status.md)에서 확장한다.
Follow-up: PG가 돌려준 거래별 주문 번호 기록은 [ADR 0098](0098-log-pg-returned-order-number-per-transaction.md)에서 확장한다.
Follow-up: 결제 결과를 확인하지 못하고 끝난 조회의 기록은 [ADR 0099](0099-log-deferred-payment-result-check.md)에서 확장한다.
Extends: [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md) (PG HTTP 요청과 결과 기록), [ADR 0032](0032-persist-payment-result-before-order-application.md) (TX B 결과 저장과 TX C 주문 반영의 실패 기록)
Related: [10주차 설계 2절](../week10/design.md#2-pg-경계-로그)

## Context

PG 생성 응답을 받지 못하면 결제 시도는 UNKNOWN으로 남는다. PG 조회로 SUCCESS를 받았지만 TX B가 rollback돼도 결제 시도는 UNKNOWN 그대로이고 결과 이력(`payment_event`)도 함께 사라진다. 두 경우 DB 상태가 같고 PG에는 SUCCESS 거래가 있다.

기존 결제 경로의 로그는 결과 충돌 `WARN`과 복구 배치 예외 `ERROR`뿐이었다. 결과를 확인하지 못한 이유(`UnknownCause`)는 메모리에서만 쓰이고 DB·로그 어디에도 남지 않았다. 그래서 위 두 경우를 관측으로 구분할 수 없었다.

PG가 그 순간 돌려준 값은 외부 API를 다시 불러야만 얻을 수 있다. 사고 중에는 그 호출이 실패할 수 있고, 다시 조회하면 현재 상태만 돌려준다.

## Decision

- `TestPgPaymentAdapter`가 PG 호출마다 요청 한 줄과 결과 한 줄을 남긴다. 로그 형식은 일반 콘솔·JSON 로그 양쪽에서 보이도록 메시지 안의 `key=value`로 쓴다.
  - 요청: `op`(create·lookup), `providerOrderId` 또는 조회한 `transactionKey`, 생성이면 우리가 보낸 `amount`
  - 받은 결과(`INFO`, `PG 응답`): `httpStatus`, `result`, 받은 거래마다 `transactionKey:PG가 준 주문 번호:status:amount`, `elapsedMs`. HTTP 상태는 [ADR 0097](0097-log-pg-response-http-status.md), 거래별 주문 번호는 [ADR 0098](0098-log-pg-returned-order-number-per-transaction.md)에서 더했다.
  - 응답은 받았지만 결과를 확인하지 못함(`WARN`, `PG 응답`): `httpStatus`, `result`(UNKNOWN), `cause`(`UnknownCause`), `error`(가장 안쪽 예외 이름), `elapsedMs`
  - 응답을 받지 못함(`WARN`, `PG 응답 없음`): `result`(UNKNOWN·NOT_SENT), `cause`(`UnknownCause`), `error`(가장 안쪽 예외 이름), `elapsedMs`
- 카드번호·카드 종류·사용자 식별자는 남기지 않는다.
- `OrderFacade`가 TX B(생성 응답·조회 결과 저장)와 TX C(주문 반영)를 부를 때 예외가 나면 `orderId`, 단계(`TX_B:<source>`·`TX_C`), 예외 이름을 `ERROR`로 남기고 예외를 그대로 던진다. 처리 결과는 바꾸지 않는다.

## 근거

- `transactionKey`: 같은 거래를 다시 조회한 것과 한 주문에 거래가 두 개 생긴 것(중복 결제)은 `providerOrderId`만으로 구분되지 않는다.
- 결과와 `error`: 응답을 받지 못한 것(②)과 받은 뒤 저장에 실패한 것(③-a)을 가른다. ②는 `PG 응답 없음`만 있고, ③-a는 `PG 응답 ... SUCCESS`와 `결제 결과 저장 실패`가 함께 있다. `error`는 read timeout(`SocketTimeoutException`)과 응답 없이 끊긴 연결(`NoHttpResponseException`)을 구분한다. 우리 코드는 둘 다 같은 `UNKNOWN(TEMPORARY_FAILURE)`로 처리하지만, 사용자 대기 시간과 원인 조사 방향이 다르다.
- PG가 준 `amount`: 금액 불일치는 복구 중단 조건이다. 그 순간 받은 값은 외부 API로만 다시 얻을 수 있고 다시 얻은 값은 현재 값이다. 금액은 민감정보가 아니다. 우리가 요청한 금액은 결제 시도에 변경 없이 저장돼 있어 따로 남기지 않는다.
- 경계에서만 남긴다: 모든 메서드에 로그를 두지 않고, 외부와 주고받는 지점과 rollback처럼 DB에 흔적이 남지 않는 지점만 남겨 양을 줄인다.
- 주문 식별: 어댑터는 `providerOrderId`(`LP-ORD-` + 주문 ID 10자리)만 받는다. 우리 주문 ID는 `OrderFacade` 로그와 결제 시도에서 이어 찾는다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| `PaymentProvider` 바깥에 로깅 decorator 추가 | 결과(`PaymentObservation`)만 보여 timeout과 연결 끊김을 구분할 예외 정보가 없다 |
| `UnknownCause`에 timeout·끊김 값을 추가 | 조회 retry·회로 판단이 이 값을 쓰므로 영향 범위가 커진다. 처리는 같고 기록만 필요하다 |
| `payment_event`에 원인 저장 | TX B가 rollback되면 함께 사라져 ③-a를 남길 수 없다 |
| 요청·응답 본문 전체 기록 | 카드번호가 포함되고 양이 늘어난다 |
| SLF4J key-value API로 구조화 필드 기록 | 현재 일반 콘솔 형식에는 key-value가 보이지 않는다 |

## Consequences

- 조회 retry는 시도마다 요청·결과 두 줄이 남는다. 회로가 열려 PG에 묻지 않은 경우는 어댑터에 닿지 않아 남지 않는다. 이 경우는 `OrderFacade`의 결과 확인 보류 로그가 남는다([ADR 0099](0099-log-deferred-payment-result-check.md)).
- TX 실패 로그는 원인 기록이며 복구 수단이 아니다. 복구는 기존처럼 같은 거래 조회로 한다.
- 로그 검색은 `providerOrderId`·`transactionKey`·`orderId`로 한다. 다른 요청(reconcile·callback·복구 배치)과 처음 결제 요청을 잇는 방법은 별도로 정한다.

## 검증

`PaymentBoundaryLogTest`

- 생성 응답을 1.5초 늦추자(read timeout 1초) 결제 시도가 UNKNOWN이 됐고 `PG 요청: op=create ... amount=3000`, `PG 응답 없음: ... result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException`이 남았다. 테스트에 쓴 카드번호는 출력에 없었다.
- 생성 응답을 끊어 UNKNOWN을 만든 뒤 PG 거래를 SUCCESS로 바꾸고, reconcile의 TX B 이력 저장에서 예외를 내자 결제 시도는 UNKNOWN·거래 키 없음으로 생성 직후와 같았다. 로그에는 `PG 응답: op=lookup ... httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000]`과 `결제 결과 저장 실패: orderId=1 step=TX_B:RECONCILE error=IllegalStateException`이 남았다. 끊긴 생성 응답은 `error=NoHttpResponseException`이었다.
