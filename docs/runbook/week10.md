# Runbook: health 는 정상인데 결제가 "확인 중"에 머문다

PG 는 결제를 처리했는데 그 응답을 우리가 받지 못해, 주문이 확정되지 않은 채 남는 경우를 다룬다.
이 문서의 명령과 검색어는 `CheckoutIncidentTest` 가 로컬 통제 환경에서 실제로 쓴 것이다. 운영 환경에서 실행해 본 적은 없다.

순서는 **증상 확인 → 영향 범위 고정 → 진단 → 복구 → 확인**이다. 원인을 다 밝히기 전에 복구해도 되는 조건을 3 장에 적었다.

## 0. 하지 않는 것

- **PG 생성 호출을 다시 보내지 않는다.** 응답을 못 받은 결제는 PG 에 거래가 있을 수 있다. 다시 보내면 이중 결제다.
- **DB 의 결제·주문 상태를 직접 고치지 않는다.** `UNKNOWN` 을 `SUCCESS` 나 `FAILED` 로 바꾸는 `update` 는 이 문서에 없다. 상태는 PG 조회 결과를 반영하는 경로로만 바뀐다.
- **복구 API 를 진단보다 먼저 부르지 않는다.** reconcile 은 조회 결과를 반영하므로, 부르는 순간 장애 상태의 증거가 사라진다.

## 1. 증상

| 보이는 것 | 어디서 |
|---|---|
| 고객 화면이 "결제 확인 중"에서 넘어가지 않는다 | 주문 조회 응답의 `status` 가 `PENDING` |
| 프로세스는 정상이다 | `GET /actuator/health` 가 `UP` |
| 응답을 받지 못한 결제가 생긴다 | `payment_create_total{result="unknown"}` 이 오른다. 응답을 잃은 그 순간 오른다 |
| 미확인 결제가 남아 있다 | `payment_unresolved{status="unknown"}` 이 0 보다 크고 줄지 않는다. 이 값은 앱이 아니라 DB 를 읽는 수집기가 낸다 |
| 끝난 결제가 늦게 끝났다 | `payment_settle_seconds` 의 30 초 구간 밖에 건수가 생긴다 |

health 가 `UP` 인 것은 이 사고와 모순이 아니다. health 는 프로세스와 의존 서비스가 응답하는지를 말하고, 결제가 끝났는지는 말하지 않는다.

정상일 때 `unknown` 은 잠깐 생겼다가 사라진다. sweeper 가 요청 후 10 초가 지난 미완료 결제를 10 초 주기로 조회해 닫기 때문이다. 로컬에서 재현했을 때 3 건이 20 초 만에 닫혔다. **사고는 이 값이 여러 주기 동안 줄지 않을 때**다. 몇 초를 넘으면 사고로 볼지의 기준값은 아직 정하지 않았다.

### 이 지표를 읽을 때 알아야 할 것

지표는 둘로 나뉘고 맡은 일이 다르다.

| 물음 | 지표 | 내는 곳 | 늦는 정도 |
|---|---|---|---|
| 지금 늘고 있는가 | `payment_create_total{result="unknown"}`, `payment_observe_total{from,to}` | 앱. 상태 변경이 커밋된 직후 올린다 | 없다 |
| 지금 몇 건 남았는가 | `payment_unresolved{status}` | sql exporter. `payment` 테이블을 상태별로 센다 | 최대 5 초 (수집 주기). 수집할 때마다 DB 를 한 번 센다 |
| 가장 오래 남은 결제가 요청된 지 얼마나 지났는가 | `payment_unresolved_oldest_seconds{status}` | sql exporter. 상태별로 가장 이른 `requested_at` 부터 지금까지의 초. 그 상태의 결제가 없으면 0 | 위와 같다 |

앱은 남은 건수를 내지 않는다. 남은 건수는 DB 에 있는 상태라서, 앱이 메모리로 따라가면 재시작할 때와 인스턴스가 여럿일 때 틀린다. 그래서 앱 밖에서 DB 를 센다.

- **빠른 감지는 앱의 counter 로 한다.** counter 는 커밋 직후 오르고 `payment_unresolved` 는 다음 수집 때 오른다. 로컬에서 초당 30 건을 걸었을 때 수집 주기가 30 초면 DB 에 49 건이 쌓인 동안 지표는 7 건이었고, 5 초로 줄이자 차이가 한 주기 안으로 들어왔다. 쿼리 한 번은 2~14 ms 였다.
- **`payment_unresolved` 가 아예 보이지 않으면** 0 이 아니라 exporter 가 죽었거나 DB 에 닿지 못한 것이다. Prometheus 의 `up{job="payment-db"}` 를 본다. 그동안은 2 장의 조회로 직접 센다.

"얼마나 오래 묶여 있는가"는 `payment_unresolved_oldest_seconds` 가 답한다. 어느 주문인지는 지표에 없다. 2 장의 조회 결과에서 `requested_at` 이 가장 이른 행이 그것이다.

### 알림

지표를 사람이 보고 있지 않아도 사고를 알 수 있게 Prometheus 규칙 다섯을 둔다. 규칙은 `docker/grafana/rules/payment.rules.yml` 에 있다.

| 알림 | 조건 | 뜻 | 먼저 할 일 |
|---|---|---|---|
| `PaymentResponsesLost` (warning) | 최근 5 분 동안 `UNKNOWN` 으로 들어온 결제가 3 건 이상 | PG 응답을 잃고 있다. 기다리지 않고 바로 울린다 | 이 문서 1 장부터. sweeper 가 닫고 있는지 `payment_unresolved` 를 본다 |
| `PaymentUnresolvedStuck` (critical) | `UNKNOWN` · `REQUESTED` · `PENDING` 중 가장 오래 남은 결제가 요청된 지 120 초를 넘겼다. 상태별로 따로 울리고 메시지에 상태가 나온다 | sweeper 가 닫지 못한 결제가 있다. 사람이 봐야 한다. `unknown` 은 응답 유실, `requested` 는 내부 반영 실패, `pending` 은 callback 이 오지 않은 것이다 | 2 장에서 목록을 고정하고 4 장으로 간다 |
| `PaymentConflict` (critical) | `CONFLICT` 결제가 한 건이라도 있다. 기다리지 않고 울린다 | PG 와 어긋나 멈췄다. sweeper 도 reconcile 도 더 건드리지 않아 저절로 닫히지 않는다 | 6 장의 중단 조건. 대사가 필요하다 |
| `PaymentDbExporterDown` (warning) | `up{job="payment-db"}` 가 0 인 채로 1 분 | 남은 건수를 못 세고 있다. 위 알림이 조용한 것은 이 때문일 수 있다 | exporter 와 DB 연결을 살리고, 그동안 2 장의 조회로 직접 센다 |
| `PurchaseActivityLagStuck` (warning) | `purchase-activity` 컨슈머의 lag 이 0 보다 큰 채로 2 분 | 확정된 주문이 구매 활동과 랭킹에 반영되지 않고 있다 | streamer 가 살아 있는지 본다. 결제 복구와는 별개다 |

기준값은 요구사항에 없어 우리가 정했다(`정함`). 임의로 고른 값은 아니고, 시스템이 스스로 회복하는 속도에서 나온다.

sweeper 는 요청된 지 10 초가 지난 미완료 결제를, 앞의 실행이 끝나고 10 초 뒤에 다시 조회해 닫는다. 한 번에 50 건까지 보고, 가장 오래 안 본 것부터 본다. 그래서 PG 가 결과를 정했고 밀린 결제가 50 건 이하이며 조회가 빨리 끝나는 평소에는, 결제가 요청된 뒤 **약 20 초** 안에 닫힌다(대상이 되기까지 10 초, 다음 실행까지 최대 10 초). 로컬에서 본 값도 20 초였다.

- **`PaymentUnresolvedStuck` 의 120 초는 그 여섯 배다.** sweeper 가 그 결제를 열 번 넘게 볼 기회가 있었는데도 닫지 못했다는 뜻이다. 울리면 넷 중 하나다.
  - PG 조회가 안 된다. `payment_lookup_total{source="sweeper"}` 의 `unavailable` 과 조회 회로를 본다.
  - sweeper 가 돌지 않는다. `payment_lookup_total{source="sweeper"}` 이 오르지 않는다.
  - 밀린 결제가 많다. 한 대가 10 초에 50 건을 닫으니 1,800 건이 밀리면 맨 뒤는 약 6 분을 기다린다(로컬 측정). `payment_unresolved` 의 합을 본다.
  - PG 가 아직 결과를 정하지 않았다. 조회는 되는데 `PENDING` 이 돌아온다. 이때는 6 장의 중단 조건을 따른다.
- **`PaymentResponsesLost` 의 "5 분에 3 건"은 계산이 아니라 판단이다.** 한두 건은 우연한 timeout 일 수 있고 sweeper 가 20 초 안에 닫는다. 5 분 안에 3 건이 넘으면 우연이 아니라 계속되는 것으로 본다.
- **`PurchaseActivityLagStuck` 의 2 분도 같은 식이다.** 컨슈머는 평소 lag 을 10 초 안에 따라잡는다(로컬 측정).
- **sweeper 설정을 바꾸면 이 기준도 다시 계산한다.** 대상이 되는 시간(10 초)과 한 번에 보는 건수(50)는 `PaymentSweeper` 의 상수이고, 주기는 `payment.sweeper.interval` 이다. 주기를 60 초로 늘리면 120 초는 두 번 볼 기회밖에 안 된다. 둘을 묶어 주는 장치는 없다.
- **응답 유실을 세는 숫자는 앱이 뜰 때 0 으로 시작한다.** 처음 잃는 순간에 숫자가 생기면 Prometheus 는 0 에서 오른 것으로 보지 못해 `PaymentResponsesLost` 가 울리지 않는다. 그래서 `payment_create_total` 은 결과 네 가지를 모두 0 으로 미리 낸다. 새로 띄운 앱에서 3 건을 잃었을 때 증가량이 3 으로 잡히고 10 초 안에 울리는 것을 봤다.
- **건수가 아니라 지난 시간을 본다.** 응답 유실이 계속 들어와도 sweeper 가 하나씩 닫고 있으면 건수는 0 보다 크지만 묶인 결제는 없다. `PaymentUnresolvedStuck` 은 그때 울리지 않고, 결제 하나가 120 초를 넘겨 남았을 때만 울린다. 알림 문구에 지난 초와 남은 건수가 들어간다.
- **뒤집을 조건**: 사고가 아닌데 울리면 올리고, 고객 문의가 알림보다 먼저 오면 내린다.
- **판정 주기는 15 초다.** 알림은 조건이 채워진 뒤 최대 15 초 늦게 울리고 꺼진다. `PaymentResponsesLost` 는 5 분 창을 보므로 유실이 멈춘 뒤에도 최대 5 분 동안 울린다.
- **전달은 Grafana 가 맡는다.** 조건은 Prometheus 규칙에만 있고, Grafana 에는 "울리고 있는 Prometheus 알림(`ALERTS{alertstate="firing"}`)을 Slack 으로 보낸다"는 규칙 하나만 있다(`docker/grafana/provisioning/alerting/`). 그래서 조건을 고칠 때는 Prometheus 규칙 파일만 고친다. Slack 메시지에는 알림 이름, 심각도, 이 문서의 링크가 들어가고 건수는 들어가지 않는다. 건수는 Prometheus 의 `/alerts` 나 대시보드에서 본다.
- **Grafana 가 죽으면 전달이 멈춘다.** 판정은 Prometheus 에서 계속되므로 `/alerts` 에서는 보인다.

## 2. 영향 범위를 고정한다

진단과 복구는 같은 주문 목록을 대상으로 한다. 먼저 목록을 뽑아 적어 둔다.

```sql
select id as payment_id, order_id, attempt_key, provider_order_id, amount,
       status, transaction_key, requested_at, last_checked_at
from payment
where status in ('UNKNOWN', 'REQUESTED', 'PENDING', 'CONFLICT')
order by requested_at;
```

- `UNKNOWN`: 생성 호출의 결과를 모른다. 이 문서의 대상이다.
- `REQUESTED`: 보냈다는 기록만 있다. 생성 응답을 반영하지 못했을 수 있다. 같은 절차로 다룬다.
- `PENDING`: PG 가 접수했고 결과를 기다린다. 오래 머물면 4 장의 가설 1 이다.
- `CONFLICT`: PG 와 우리 기록이 어긋나 멈췄다. 이 문서로 복구하지 않는다(6 장).

이 조회는 읽기만 한다. 목록의 `order_id` · `provider_order_id` 를 이후 모든 단계에서 그대로 쓴다.

## 3. 원인을 다 밝히기 전에 복구해도 되는 조건

고객 피해가 이어지는 중이면 상세한 원인 분석보다 복구가 먼저다. 다만 아래 셋을 주문마다 확인한 뒤에만 5 장으로 간다.

1. PG 에 그 주문의 거래가 **정확히 한 건** 있다.
2. 그 거래의 주문 식별자와 금액이 우리 `payment` 행과 같다.
3. 그 거래의 상태가 `SUCCESS` 또는 `FAILED` 로 끝나 있다.

확인은 PG 의 조회 API 로 한다. 읽기 전용이다.

```
GET {pg.base-url}/api/v1/payments?orderId={provider_order_id}
```

셋 중 하나라도 아니면 6 장의 중단 조건이다.

## 4. 진단: 같은 증상, 다른 원인

"PG 는 `SUCCESS` 인데 우리는 `UNKNOWN`" 이라는 조합만으로는 원인을 가를 수 없다. 로그의 경계 기록까지 봐야 한다.

| 가설 | PG 조회 | adapter 로그 | application 로그 |
|---|---|---|---|
| 1. PG 가 아직 처리 중 | `PENDING` | 없음 | 없음 |
| 2. PG 는 처리했는데 응답을 잃었다 | `SUCCESS` | `payment_create_no_response` 또는 `payment_create_unexpected_response` | `payment_create_unknown` 이 있고 `payment_apply_failed` 는 없다 |
| 3. 조회는 됐는데 내부 반영이 실패했다 | `SUCCESS` | 없을 수 있다 | `payment_apply_failed` 가 있다 |

### 로그 검색

JSON 출력(dev · qa · prd)에서는 필드로 찾는다. 로컬의 plain 출력에서는 같은 값이 문장 안에 `이름=값` 으로 있다.

Loki 라면 조회할 때 JSON 을 풀어 필드로 거른다. 로컬에서 이 질의로 응답을 잃은 3 건이 나오는 것을 확인했다.

```
{app="commerce-api"} | json | event="payment_create_no_response"
{app="commerce-api"} | json | providerOrderId="LP-ORD-0000000013"
```

| 찾는 것 | 필드 조건 |
|---|---|
| 한 주문의 모든 결제 로그 | `providerOrderId = "LP-ORD-…"` |
| 응답을 못 받은 생성 호출 | `event = "payment_create_no_response"`, 원인은 `cause` (`timeout` · `connection_lost`) |
| 해석하지 못한 응답 | `event = "payment_create_unexpected_response"`, 상태 코드는 `status` |
| 결과를 모르게 된 결제 | `event = "payment_create_unknown"` |
| 내부 반영 실패 | `event = "payment_apply_failed"`, 경로는 `source`, 오류 종류는 `error` |
| 결과를 모르던 결제가 닫힌 기록 | `event = "payment_status_changed"`, `source` · `from` · `to` |
| PG 와 어긋나 멈춘 결제 | `event = "payment_conflict"`, `source` · `from` · `to` |

정상 결제는 줄을 남기지 않는다. 접수된 생성 호출도, callback 이나 sweeper 가 `PENDING` 을 닫은 것도 로그에 없다. 그 수는 지표로 본다. 상태가 바뀐 줄은 세 경우에만 남는다.

- `UNKNOWN` 이던 결제가 닫혔다. 앞서 남은 "결과를 모른다" 줄의 끝이다.
- 생성 응답을 반영하지 못해 `REQUESTED` 로 남았던 결제가 조회로 닫혔다. 앞서 남은 "반영하지 못했다" 줄의 끝이다.
- `CONFLICT` 가 됐다.

그래서 한 주문을 `providerOrderId` 로 검색해 아무 줄도 없으면 그 결제는 정상 경로로 끝난 것이다.

adapter 의 줄은 `providerOrderId` 만, application 의 줄은 `paymentId` · `orderId` · `providerOrderId` 를 갖는다. 두 줄을 잇는 값은 `providerOrderId` 다.

요청의 trace id 로는 잇지 못한다. 최초 결제 요청과 나중의 reconcile 은 서로 다른 요청이라 trace id 가 다르다. 로컬에서 trace 를 수집해 확인했다. 최초 요청의 trace 에는 실패한 PG 호출이, reconcile 의 trace 에는 성공한 PG 조회가 따로 들어 있었다.

### 판정

- 2 번이면 5 장으로 간다.
- 3 번이면 `error` 를 본다. 잠금 대기 실패면 일시적이고 다음 조회에서 풀릴 수 있다. 다른 오류면 DB 상태를 먼저 본다. 복구 API 를 불러도 같은 자리에서 다시 실패한다.
- 1 번이면 기다린다. PG 가 끝내지 않은 거래를 우리가 끝낼 수 없다.
- 로그가 없어서 2 번과 3 번을 가르지 못하면 **"미확정"으로 적고** 3 장의 조건만으로 복구 여부를 정한다.

## 5. 복구

대상은 2 장에서 고정한 목록 중 3 장의 조건을 통과한 주문이다.

주문마다 기존 reconcile API 를 부른다. 이 호출은 PG 를 조회해 그 결과를 주문에 반영한다. PG 생성 호출은 보내지 않는다.

```
POST /api/v1/orders/{orderId}/payment/reconcile
X-USER-ID: {주문 소유자}
```

- 응답의 `orderStatus` 가 `CONFIRMED`(또는 PG 가 실패였으면 `FAILED`)이면 그 주문은 끝났다.
- `lookupUnavailable` 이 `true` 면 PG 조회가 실패한 것이다. 아무것도 바뀌지 않았다. 6 장으로 간다.
- 같은 주문에 다시 불러도 결과는 같다. 확정 · 재고 · outbox 가 늘지 않는다.

이 API 는 본인 주문만 받는다. 운영자가 고객을 대신해 부르는 전용 경로는 아직 없다.

## 6. 중단 조건

아래 중 하나면 그 주문은 복구하지 않고 상태를 그대로 둔다.

| 관찰 | 뜻 | 다음 |
|---|---|---|
| PG 조회가 실패한다 | PG 의 사실을 모른다 | PG 조회가 되돌아올 때까지 기다린 뒤 3 장부터 다시 |
| PG 에 거래가 0 건이다 | 요청이 PG 에 닿지 않았을 수 있고, 아직 보이지 않는 것일 수도 있다 | 실패로 닫지 않는다. 언제 실패로 닫아도 되는지는 정해지지 않았다 |
| PG 에 거래가 2 건 이상이다 | 이중 접수 가능성 | 결제가 `CONFLICT` 로 멈춘다. `payment_discrepancy` 를 보고 PG 와 대사한다 |
| 금액이나 주문 식별자가 다르다 | 다른 거래다 | 위와 같다 |
| PG 가 계속 `PENDING` 이다 | PG 가 끝내지 않았다 | 기다린다 |
| reconcile 이 503 `LOCK_UNAVAILABLE` 을 돌려준다 | 잠금 대기로 반영하지 못했다. 일시적이다 | 잠시 뒤 같은 주문에 다시 부른다 |
| reconcile 이 500 을 돌려준다 | 잠금이 아닌 이유로 내부 반영이 실패했다 | `payment_apply_failed` 의 `error` 를 보고 DB 상태부터 확인한다 |

`CONFLICT` 가 된 결제는 조회와 sweeper 가 더 건드리지 않는다. 어긋난 내용은 `payment_discrepancy` 에 한 줄씩 남는다.

```sql
select payment_id, reason, source, observed_transaction_key, observed_status, observed_amount, observed_at
from payment_discrepancy
where payment_id in (/* 2 장의 payment_id */)
order by observed_at;
```

## 7. 복구 확인

2 장의 **같은 주문 목록**으로 확인한다. 새 주문이나 다른 주문을 섞지 않는다.

| 확인 | 기대 | 방법 |
|---|---|---|
| 결제 상태 | 목록 전부 `SUCCESS` 또는 `FAILED`, `UNKNOWN` 0 | 2 장의 조회를 `order_id` 로 다시 |
| 주문 상태 | 결제가 `SUCCESS` 인 주문은 `CONFIRMED` | `GET /api/v1/orders/{orderId}` |
| PG 생성 호출 | 복구 전후로 늘지 않음 | `payment_create_total` 의 합이 그대로 |
| PG 거래 | 주문당 한 건, 복구 전과 같은 거래 키 | 3 장의 PG 조회 |
| 재고 | 복구로 더 줄지 않음 | 확정은 이미 묶어 둔 수량을 그대로 쓴다 |
| 미확인 지표 | `payment_unresolved{status="unknown"}` 이 0 | 최대 5 초 늦게 반영된다 |
| 걸린 시간 | 복구한 결제가 `payment_settle_seconds` 에 긴 구간으로 남는다 | 사고가 끝난 뒤에도 얼마나 늦었는지가 남는다 |
| 복구 기록 | 주문마다 `payment_status_changed`, `source=reconcile`, `from=UNKNOWN` 이 한 줄 | 4 장의 로그 검색 |
| 후속 이벤트 | 주문마다 `order_event_outbox` 한 행, relay 뒤 `PUBLISHED` | `select order_id, event_id, status from order_event_outbox where order_id in (…)` |

outbox 가 `PUBLISHED` 인 것은 메시지가 Kafka 에 나갔다는 뜻이다. 그 뒤는 지표 둘로 본다.

| 물음 | 지표 | 정상 |
|---|---|---|
| 아직 안 나간 이벤트가 있는가 | `order_outbox_unpublished{status}` (sql exporter) | `pending` 이 잠깐 올랐다가 0, `blocked` 는 0 |
| 나간 메시지를 컨슈머가 읽었는가 | `kafka_consumergroup_lag{consumergroup="purchase-activity"}` (kafka exporter) | 0 으로 돌아온다 |

lag 이 0 이면 streamer 가 메시지를 읽었다는 뜻이다. 읽은 메시지가 구매 활동 행으로 남았는지, 주간 랭킹에 반영됐는지는 streamer 와 batch 쪽에서 따로 확인한다.

## 8. 재진입

복구를 중단했다가 다시 시작할 때는 2 장부터 다시 한다. 목록을 새로 뽑으면 그 사이 sweeper 나 callback 이 닫은 주문이 빠진다.

- reconcile 은 여러 번 불러도 안전하다. 끝난 결제는 다시 조회하지 않고, 같은 결과는 한 번만 반영된다.
- 2 초 안에 같은 주문을 다시 부르면 조회를 건너뛰고 현재 상태를 돌려준다. 실패가 아니다.
- 목록에 `CONFLICT` 가 새로 생겼으면 그 주문은 6 장으로 보낸다.

## 9. 이 문서가 확인한 범위

| 확인함 | 확인하지 않음 |
|---|---|
| 응답 timeout 2 건과 연결 끊김 1 건이 `UNKNOWN` 으로 남고, 같은 주문의 reconcile 로 생성 호출 증가 없이 확정된다 | 운영 환경, 실제 PG, 부하가 있는 상황 |
| 그동안 health 는 `UP`, `UNKNOWN` 으로 들어온 수는 0 → 3, 나간 수는 0 → 3 | 알림의 기준값이 운영에서 맞는지. 전달은 로컬에서 테스트용 Slack 채널로만 보내 봤다 |
| 로컬에서 앱을 띄워 같은 사고를 재현하고 Prometheus · Loki · Tempo 에서 지표, 로그, trace 를 본다 | sql exporter 가 내는 값을 자동 테스트로. 로컬에서 초당 30 건 부하를 걸어 DB 를 직접 센 값과 5 초 안에 같아지는 것, 앱을 내려도 값이 그대로 나오는 것을 손으로 봤다 |
| 가설 2 와 3 을 로그로 가른다 | 가설 3(내부 반영 실패)이 실제로 난 상태에서의 복구 절차 전체 |
| 알림 다섯이 조건대로 울리고 꺼진다(`promtool` 로 조건을 확인했고 규칙 테스트 파일은 저장소에 두지 않았다. 로컬에서 실제로 울려 본 것은 응답 유실, `UNKNOWN` 정체, exporter 중단, 컨슈머 lag 넷이다. `REQUESTED` · `PENDING` 정체와 `CONFLICT` 알림은 `promtool` 로만 확인했다) | 실제 Kafka · DB 장애에서 exporter 자신이 어떻게 동작하는지 |
| 복구한 주문의 메시지가 실제 Kafka 에 주문당 한 번 나간다 | 그 메시지가 구매 활동과 주간 랭킹까지 이어지는 것을 같은 실행으로 |
