# 10주차 결제 로그 지도 — 언제 어떤 줄이 남는가

## 주문 1번의 실제 기록 읽기

주문 1번의 PG 주문 번호는 `LP-ORD-0000000001`이다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`에서 이 번호로 생성 요청과 두 번의 조회를 찾을 수 있다.

| 원문 줄 | 읽을 부분 | 주문 1번에 일어난 일 |
| --- | --- | --- |
| 1–2 | `op=create` → `SocketTimeoutException` | 3,000원 결제를 요청했다. 1,138ms를 기다렸지만 첫 응답을 받지 못했다. |
| 3–4 | `op=lookup` → `TEST-TX-1` · `SUCCESS` · `3000` | 진단 GET에서 PG가 이미 결제를 성공 처리했음을 확인했다. |
| 5 | `[before-reconcile]` · `attempt=UNKNOWN` · `reservation=RESERVED` | 같은 시점에 우리 DB에는 아직 미확인 결제와 재고 예약이 남아 있었다. |
| 6–7 | `op=lookup` → 같은 `TEST-TX-1` · `SUCCESS` | reconcile이 PG에 다시 조회해 같은 결제 결과를 받았다. |
| 8 | `[after-reconcile]` · `order=CONFIRMED` · `pgCreates=1` | 우리 DB에 주문 확정이 반영됐다. 결제 생성 횟수는 여전히 한 번이다. |

두 GET 모두 PG 로그에는 `op=lookup`으로 찍힌다. 이 실행에서 첫 GET을 진단 조회, 둘째 GET을 복구 조회로 구분할 수 있는 근거는 테스트가 5번째 줄을 출력한 직후 reconcile을 호출했다는 점이다. 5·8번째 줄은 테스트가 직접 읽은 상태를 보여 준다.

결제 한 건이 PG와 주고받는 순서대로, 어느 지점에 어떤 로그가 남는지 예시로 정리한다. 로그 필드를 정한 이유는 [design.md 2절](design.md#2-pg-경계-로그)과 ADR [0096](../adr/0096-log-pg-boundary-and-failed-result-save.md)~[0099](../adr/0099-log-deferred-payment-result-check.md)에 있다.

이 로그로 사고를 진단하고 복구하는 절차는 [runbook](../runbook/week10.md)에 있다.

예시의 주문 ID는 1, PG 주문 번호는 `LP-ORD-0000000001`(`LP-ORD-` + 주문 ID 10자리), 금액은 3,000원이다. `elapsedMs`는 테스트에서 실제로 관찰한 값만 숫자로 쓰고, 나머지는 `<ms>`로 둔다.

## 1. 한 장으로 보기

```
[1번 주고받기: 결제 요청]
  ① 우리 → PG   "결제해 줘"            PG 요청: op=create ...
                  (PG 안에서 처리 — 우리 쪽에서는 볼 수 없어 로그 없음)
  ② PG → 우리   "접수했어 (PENDING)"    PG 응답: op=create ...        / 못 받으면 PG 응답 없음
  ⑤ ②를 DB에 저장하다 실패              결제 결과 저장 실패: ... step=TX_B:CREATE_RESPONSE

[2번 주고받기: 결과 확인 — reconcile·callback·복구 배치]
  ③ 우리 → PG   "결제 됐어?"           PG 요청: op=lookup ...
  ④ PG → 우리   "됐어 (SUCCESS)"        PG 응답: op=lookup ...        / 못 받으면 PG 응답 없음
  ⑤ ④를 DB에 저장하다 실패              결제 결과 저장 실패: ... step=TX_B:<source>
  ⑥ 주문 확정(TX C)하다 실패            결제 결과 저장 실패: ... step=TX_C

  ③' 결과를 확인 못 하고 미룸           결제 결과 확인 보류: ...       (서킷이 열렸으면 ③④ 없이 이 줄만)
```

- ①②③④는 `TestPgPaymentAdapter`, ③'⑤⑥은 `OrderFacade`가 남긴다.
- callback은 PG가 우리 API를 부르는 것이다. callback을 받으면 그 내용을 믿지 않고 ③④로 PG에 다시 묻는다. callback 수신 자체를 따로 남기는 로그는 없다.
- 조회는 재시도할 수 있다. 재시도마다 ③④ 두 줄이 다시 남는다.

## 2. 지금 남는 로그 종류

| 문구 | 레벨 | 남기는 곳 | 언제 |
| --- | --- | --- | --- |
| `PG 요청` | INFO | `TestPgPaymentAdapter` | PG에 생성·조회 요청을 보낼 때마다 |
| `PG 응답` (거래 확인) | INFO | `TestPgPaymentAdapter` | HTTP 응답을 받았고 거래를 읽었거나(OBSERVED), 거래 미생성이 입증된 거절(REJECTED) |
| `PG 응답` (결과 모름) | WARN | `TestPgPaymentAdapter` | HTTP 응답을 받았지만 결과를 판단할 수 없음(5xx, 근거 없는 422, 해석할 수 없는 조회 본문 등) |
| `PG 응답 없음` | WARN | `TestPgPaymentAdapter` | HTTP 응답을 받지 못함(timeout, 연결 끊김, 연결 풀 부족, 연결 실패). 생성 응답이 202가 아니거나 본문이 PENDING 거래가 아닌 경우도 현재는 이 문구로 남는다(결정 대기) |
| `결제 결과 확인 보류` | WARN | `OrderFacade` | reconcile·callback·복구 배치의 조회 결과가 UNKNOWN이라 저장 없이 끝남 (4절) |
| `결제 결과 저장 실패` | ERROR | `OrderFacade` | TX B·TX C가 예외로 끝남. 실패한 단계가 롤백되고 앞서 커밋된 상태는 남는다 |
| `PG 결과 충돌` | WARN | `OrderPaymentTransactions` | PG 결과가 저장된 결과·주문 번호·금액과 맞지 않음 (5주차부터) |
| `Payment recovery failed` | ERROR | `PaymentRecoveryBatch` | 복구 배치에서 주문 하나의 처리가 예외로 끝남 (5주차부터) |

`PG 응답`의 거래 목록은 `거래 키:PG가 준 주문 번호:상태:금액`이다. 카드번호·카드 종류·사용자 식별자는 어느 로그에도 남기지 않는다.

### 로그별로 남기는 이유

"불필요한 로그는 남기지 않는다", "결제가 안 됐다면 먼저 DB를 본다"는 기준으로 이번 주에 추가한 로그를 점검했다. DB와 겹치는 로그도 있지만, 각 줄이 DB만으로는 답할 수 없는 경우를 맡고 있어 지울 로그는 없었다.

| 로그 | DB와 겹치는 부분 | 그래도 남기는 이유 |
| --- | --- | --- |
| `PG 요청` | 결제 시도는 PG 호출 전에 `REQUESTED`로 저장된다(`OrderPaymentTransactions.start`) | PG 호출 도중 서버가 죽거나 재시작되면 응답 줄은 남지 않는다. 그때 "PG에 보냈을 수 있다"를 보여 주는 경계 기록이다. 다시 보내면 중복 결제가 될 수 있어 이 사실이 중요하다. 요청 줄에는 그 시각에 보낸 금액이 있어 응답 줄과 바로 대조할 수 있다. 경계의 request·response를 한 쌍으로 남긴다 |
| `PG 응답` (정상 결과) | 정상 처리되면 TX B가 같은 결과를 `payment_event`에 저장한다 | 응답을 받는 순간에는 TX B가 실패할지 알 수 없다. 실패하면 rollback으로 DB 기록도 사라지고 이 줄만 남는다(③-a). 실패할 때만 남기는 방법은 없어서 받은 순간 남긴다 |
| `PG 응답` (결과 모름)·`PG 응답 없음` | 결제 시도는 `UNKNOWN`으로 남지만 이유(`UnknownCause`)와 HTTP 상태는 DB에 없다 | timeout·연결 끊김·5xx·해석할 수 없는 본문을 구분해야 원인 조사 방향이 정해진다 |
| `결제 결과 저장 실패` | 없음 | 실패한 단계의 변경은 롤백된다. TX C 실패 때는 앞서 저장한 결제 결과를 DB에서 확인할 수 있다 |
| `결제 결과 확인 보류` | 없음 | 조회 결과가 UNKNOWN이면 저장 없이 끝나 DB에 흔적이 없다. 서킷이 열렸으면 어댑터 로그도 없다 |

결제 한 건에 남는 PG 로그는 보통 4줄이다(생성 요청·응답, 조회 요청·응답). 조회를 재시도하면 시도마다 2줄이 늘고, 저장 실패·보류 로그는 그 일이 생겼을 때만 남는다.

## 3. 장면별 예시

### 정상 결제

```
PG 요청: op=create providerOrderId=LP-ORD-0000000001 amount=3000
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=202 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:PENDING:3000] elapsedMs=<ms>
PG 요청: op=lookup transactionKey=TEST-TX-1
PG 응답: op=lookup transactionKey=TEST-TX-1 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=<ms>
```

생성 응답이 202·PENDING인 것은 접수됐다는 뜻이다. 결제 성공은 ④의 SUCCESS로 안다.

### 생성 응답 유실 (이번 사고)

```
PG 요청: op=create providerOrderId=LP-ORD-0000000001 amount=3000
PG 응답 없음: op=create providerOrderId=LP-ORD-0000000001 result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException elapsedMs=1129
```

거래 키를 받지 못했으므로 나중에 주문 번호로 조회한다.

```
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=25
```

응답 없이 연결이 바로 끊기면 `error=NoHttpResponseException`이 남는다(관찰값 `elapsedMs=2`). 결제 판정은 timeout과 같은 `UNKNOWN(TEMPORARY_FAILURE)`이다.

### 조회로 SUCCESS를 받았지만 저장 실패 (③-a)

```
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=25
결제 결과 저장 실패: orderId=1 step=TX_B:RECONCILE error=IllegalStateException
```

DB 상태는 응답 유실과 같다(결제 시도 UNKNOWN, 거래 키 없음). 결제 이력도 생성 응답 유실 때 남은 `CREATE_RESPONSE · APPLIED · 관찰 거래 없음` 한 줄뿐이다. 이 조회의 SUCCESS 응답과 마지막 실패 줄이 함께 있어 TX B에서 멈춘 것을 안다. 실패 줄을 찾지 못했다면 그 사실만으로 저장 성공 여부를 판단할 수 없다.

### PG가 503을 돌려줌

```
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=503 result=UNKNOWN cause=TEMPORARY_FAILURE error=ServiceUnavailable elapsedMs=<ms>
```

응답은 받았으므로 `PG 응답 없음`이 아니다.

### 생성 422

```
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=422 result=REJECTED transactions=[] elapsedMs=<ms>
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=422 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=UnprocessableEntity elapsedMs=<ms>
```

첫 줄은 거래 미생성 근거가 계약과 일치한 경우(INFO), 둘째 줄은 근거가 없는 경우(WARN)다.

### PG가 다른 주문의 거래를 돌려줌

```
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=202 result=OBSERVED transactions=[T-1:OTHER-ORDER:PENDING:8000] elapsedMs=<ms>
```

앞의 `providerOrderId`는 우리가 요청한 값, 거래 목록의 주문 번호는 PG가 준 값이다. 이어서 TX B가 대조해 확정하지 않고 `PG 결과 충돌`과 `ORDER_MISMATCH` 이력을 남긴다.

### 조회 서킷이 열려 있음

```
결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN
```

`ResilientPaymentProvider`가 PG에 묻지 않고 `UNKNOWN(CIRCUIT_OPEN)`을 돌려준다. 어댑터에 닿지 않아 ③④가 없고, DB에도 저장하지 않는다. 보류 로그를 넣기 전에는 이 경우 남는 줄이 하나도 없었다.

### 조회 timeout으로 결과를 확인하지 못함

```
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답 없음: op=lookup providerOrderId=LP-ORD-0000000001 result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException elapsedMs=502
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답 없음: op=lookup providerOrderId=LP-ORD-0000000001 result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException elapsedMs=502
결제 결과 확인 보류: orderId=1 source=RECONCILE cause=TEMPORARY_FAILURE
```

조회 응답을 0.7초 늦춘 테스트(조회 read timeout 0.5초)에서 관찰한 순서다. 조회 재시도마다 어댑터 2줄이 남고, 조회 예산 안에서 결과를 얻지 못하면 마지막에 보류 한 줄이 남는다.

연결 풀에서 연결을 빌리지 못한 조회는 어댑터 안에서 끝나므로 `PG 응답 없음: ... cause=CONNECTION_UNAVAILABLE`이 남는다. `UnknownCause.CONCURRENCY_LIMIT`는 정의만 있고 현재 이 값을 만드는 코드는 없다.

## 4. 결과 확인 보류

`OrderFacade.resolve()`에서 조회 결과가 UNKNOWN이면 결과를 저장하지 않고 끝난다. 이 지점에 주문 ID와 이유를 남긴다.

```
결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN
```

| 필드 | 값 |
| --- | --- |
| `orderId` | 우리 주문 ID |
| `source` | 확인을 시작한 경로: `RECONCILE`·`CALLBACK`·`BATCH` |
| `cause` | `UnknownCause` |

정상 결제에서는 남지 않고, 장애 중 결과 확인 시도마다 한 줄이 남는다. 레벨은 WARN이다. 실패로 확정한 것이 아니라 결과를 모르는 채 끝난 것이어서 ERROR가 아니고, 주문이 확인 중에 머문다는 신호라 INFO보다 높다. [ADR 0099](../adr/0099-log-deferred-payment-result-check.md)

**정한 범위: 모든 UNKNOWN에 남긴다 (방법 1)**

주문 ID 하나로 서킷 열림·timeout 등 모든 보류 장면을 찾기 위해서다. 정상 결제에서는 남지 않고, PG에 물어본 timeout·5xx에서만 어댑터 로그 2줄에 한 줄이 더 붙는다. 검토한 두 방법:

| | 방법 1: 모든 UNKNOWN | 방법 2: PG에 묻지 않은 경우만 |
| --- | --- | --- |
| 서킷 열림 | `결제 결과 확인 보류 ... cause=CIRCUIT_OPEN` | 같음 |
| 조회 timeout | 어댑터 2줄 + `결제 결과 확인 보류 ... cause=TEMPORARY_FAILURE` | 어댑터 2줄만 |
| `orderId=1`로 검색 | 두 장면 모두 나온다 | 서킷 열림만 나온다. timeout은 `LP-ORD-0000000001`로 다시 찾는다 |

## 5. 로그가 아니라 지표로 보는 것

PG 조회 서킷 상태(닫힘·열림·반열림)는 지표로 본다. 시스템 전체에 하나이고, 지금 열려 있는지와 얼마나 오래 열려 있었는지를 그래프·알림으로 봐야 하기 때문이다. 현재는 서킷 상태 로그·지표가 모두 없다. 만드는 방법은 아직 정하지 않았다.

| 질문 | 보는 곳 |
| --- | --- |
| 지금 DB에 결과를 모르는 결제가 몇 건 남았나? | `/actuator/prometheus`의 `commerce_payment_attempts_unknown` |
| 지금 서킷이 열려 있나, 언제부터인가 | 지표 (예정) |
| 주문 1번은 왜 아직 확인 중인가 | 로그 (`orderId=1`, `LP-ORD-0000000001`) |

## 6. 주문 하나 찾기

1. `orderId=1`로 `OrderFacade`의 로그(저장 실패·보류)를 찾는다.
2. `LP-ORD-0000000001`로 어댑터의 생성·주문 번호 조회 로그를 찾는다.
3. 그 로그에서 거래 키(`TEST-TX-1`)를 얻으면, 거래 키 조회 로그도 찾는다.

D에서 주문 13을 이 키들로 추적한 뒤 최초 요청의 traceId를 따로 저장하지 않기로 했다. 성공 시 로그를 남기지 않는 단계는 결제 이력·Outbox·구매 활동을 확인한다. [결정 근거](design.md#6-요청을-잇는-키)

B의 첫 실행에서는 같은 PG 주문 번호로 생성 요청, 1,003ms 뒤의 생성 timeout, 진단용 조회 요청, 200·SUCCESS 조회 결과를 순서대로 찾았다. 마지막 `[before-reconcile]` 줄은 테스트가 DB를 읽어 출력한 값이다. 이 실행의 조회는 사고 상태를 확인하려고 호출했으며, 주문은 그 시점에도 `PAYMENT_PENDING`이었다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

복구까지 수행한 새 실행에는 같은 PG 주문 번호로 200·SUCCESS가 두 번 나온다. 첫 번째는 진단 GET이고, 두 번째는 reconcile이 보낸 GET이다. 두 결과 줄이 설명하는 것은 각 조회 시점의 PG 상태다. 내부 주문 확정과 재고는 마지막 `[after-reconcile]`에서 확인한다. 이 줄은 테스트가 API 응답과 DB 관찰값을 출력한 기록이다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`
