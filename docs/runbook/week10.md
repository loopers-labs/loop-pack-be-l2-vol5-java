# Runbook — 결제가 "확인 중"에 머문다

처음 보는 사람이 앞면만 읽고 따라 해도 중복 결제 같은 더 큰 사고를 내지 않게 하는 문서다. 앞면은 행동, 뒷면은 찾아보는 자료다. 이유와 실험 근거는 뒷면 F에 모았다.

---

# 앞면

## 0. 이 문서를 여는 때

- Grafana "결제를 시작한 뒤 끝나지 않은 주문 중 가장 오래된 주문의 경과 시간"이 계속 늘어난다. 결제 시도 상태와 관계없이 주문 기준이라 `REQUESTED`·`PENDING`·`UNKNOWN`과 주문 반영(TX C)만 실패한 주문이 모두 여기 보인다. 몇 초부터 이 문서를 열지는 정하지 않았다(E).
- Grafana "응답을 받지 못한(UNKNOWN) 결제 시도 수"가 0보다 크고 줄지 않는다. 응답 유실만 센다.
- 고객이 결제가 "확인 중"에서 끝나지 않는다고 문의한다. 주문은 `PAYMENT_PENDING`, 예약은 `RESERVED`다.

health가 `UP`이어도 이 문서의 대상일 수 있다. health는 서버와 DB·Redis 연결 상태만 보고, 결제가 끝나는지는 보지 않는다.

## 1. 하지 말 것

- **결제를 다시 요청하지 않는다.** PG는 이미 결제를 끝냈을 수 있다. 생성 요청을 다시 보내면 중복 결제가 된다.
- **DB 상태를 손으로 고치지 않는다.** 결과는 PG에 다시 물어 서비스가 반영하게 한다.
- **서버를 재시작하지 않는다.** 이미 생긴 PG 거래를 확인해 주지 않고, 진행 중인 호출을 끊는다.
- **UNKNOWN 지표가 0이라고 끝났다고 보지 않는다.** 응답 유실만 센다. 저장 실패·PG 미완료·주문 반영 실패는 주문 기준 경과 시간에서 본다. 두 지표 모두 최대 집계 주기(5초)만큼 늦다.

## 2. 주문마다 질문 순서

주문 ID 목록을 먼저 고정한다. 진행 중에 새로 생긴 주문은 섞지 않고 다음 목록으로 넘긴다. 한 주문씩 위에서부터 묻고, 그 주문의 "끝났는지 확인"까지 마친 뒤 다음 주문으로 넘어간다.

```text
1. 이미 끝났나? — DB에서 결제 시도·주문·예약을 본다
   ├─ 예 (SUCCESS·CONFIRMED·CONSUMED 또는 FAILED·PAYMENT_FAILED·RELEASED) → 완료
   └─ 아니오 ↓

2. PG에 물어볼 수 있나? — PG에 진단 조회를 한 번 한다
   ├─ 아니오 (timeout·연결 끊김·5xx·회로 열림) → 대기
   └─ 예 ↓

3. 답이 하나로 맞나? — 조회 응답을 DB와 대조한다
   ├─ 거래 1건, SUCCESS/FAILED, 주문 번호·금액·저장된 키 일치 → 진행
   ├─ 거래 2건 이상, 주문 번호·금액·키 불일치, 해석 불가 → 멈춤
   └─ 아직 PENDING 또는 0건 ↓

4. 기다려도 답이 안 생기나? — 결제 시도를 만든 뒤 지난 시간을 본다
   ├─ PENDING, 또는 0건인데 10분 전 → 대기
   └─ 0건인데 10분 넘음 → 멈춤
```

| 다음 조치 | 할 일 |
| --- | --- |
| 완료 | "끝났는지 확인"만 하고 끝낸다 |
| 진행 | reconcile 호출 → "끝났는지 확인" → 다음 주문 |
| 대기 | 상태를 그대로 두고 주문 ID·시각·조회 결과를 인계한다. 회로 회복, PG 알림 등 다시 물을 근거가 생기면 2번부터 다시 한다 |
| 멈춤 | 추가 호출을 멈추고 마지막 응답과 DB 상태를 보존한다. 사람이 결정한다 |

## 3. 조치 명령

진단 조회 (읽기 전용, 결제를 만들지 않는다). 응답과 조치 전 DB 상태를 보존한다.

```
GET {PG base-url}/api/v1/payments?orderId=LP-ORD-<주문 ID 10자리>
GET {PG base-url}/api/v1/payments/{transactionKey}      # 저장된 거래 키가 있을 때
X-USER-ID: <우리 서비스의 PG 계정 ID>
```

reconcile (진행일 때만).

```
POST /api/v1/orders/{orderId}/payment/reconcile
X-USER-ID: <주문자 ID>
```

진행하기 전에 주문마다 근거를 한 줄로 쓴다.

> 생성 요청은 timeout됐다. 주문 번호로 조회하니 거래는 한 건, SUCCESS, 주문 금액과 같은 1,000원이었다. DB는 UNKNOWN·RESERVED다. 그래서 reconcile을 진행한다.

## 4. 끝났는지 확인

| | PG `SUCCESS` | PG `FAILED` |
| --- | --- | --- |
| reconcile 응답 | `checked=true` | `checked=true` |
| 결제 시도 | `SUCCESS`, 거래 키·금액이 진단 조회와 같음 | `FAILED` |
| 주문·예약 | `CONFIRMED`·`CONSUMED` | `PAYMENT_FAILED`·`RELEASED` |
| PG 생성 요청 | 복구 중 늘지 않음 | 복구 중 늘지 않음 |

`checked=false`이거나 조합이 어긋나면 **멈춤**. PG 거래와 DB를 다시 대조할 때까지 완료 처리를 보류한다. 확정된 주문의 후속 효과(Outbox, 구매 활동, 랭킹)는 C에서 확인한다.

---

# 뒷면

## A. 질문마다 볼 기록

### A-1. DB — 몇 건, 어느 주문, 어떤 상태인가

```sql
-- 결제를 시작했는데 끝나지 않은 주문. 결제를 먼저 시작한 순서
SELECT o.id AS order_id, a.provider_order_id, a.amount, a.status AS attempt_status,
       a.transaction_key, a.created_at AS payment_started_at
FROM orders o JOIN payment_attempt a ON a.order_id = o.id
WHERE o.status = 'PAYMENT_PENDING'
ORDER BY a.created_at;
```

주문 기준으로 찾는다. 결제 시도 상태로 찾으면 주문 반영(TX C)만 실패해 결제 시도가 `SUCCESS`인 주문이 빠진다. 경과 시간은 질의에서 계산하지 않는다. 저장된 시각과 DB의 현재 시각은 시간대 해석이 다를 수 있고, 실험에서 저장 시각을 JDBC로 직접 읽었을 때 9시간 어긋났다. 오래된 순서와 Grafana의 경과 시간을 함께 본다.

정상 결제도 잠깐 이 목록에 있다. 로컬 실험(PG가 2초 뒤 확정한다고 가정)에서 정상 주문은 결제 시작 뒤 2.3초 안에 목록에서 빠졌다. 운영 기준은 아니다.

| `attempt_status` | 뜻 | 다음 |
| --- | --- | --- |
| `REQUESTED` | 생성 응답을 저장하지 못했거나, PG 호출 전·도중에 멈췄다 | A-2 로그로 단계를 가른 뒤 2절 |
| `UNKNOWN` | 생성 응답을 받지 못했다 | 2절 |
| `PENDING` | PG 접수까지 확인했고 최종 결과를 받지 못했다 | 2절 2번 진단 조회 |
| `SUCCESS`·`FAILED` | 결제 결과는 저장했고 주문 반영(TX C)이 끝나지 않았다 | B의 `step=TX_C` 규칙 |

배치 요약에 `unfinished`가 남으면 미완료 주문의 마지막 배치 조회 결과를 확인한다. 아래 질의는 배치가 실제로 이력을 남겼고 주문이 아직 대기 중인 건을 찾는다.

```sql
SELECT a.order_id, a.provider_order_id, a.status, e.created_at, e.reason
FROM payment_attempt a
JOIN orders o ON o.id = a.order_id
JOIN payment_event e ON e.id = (
    SELECT MAX(b.id) FROM payment_event b
    WHERE b.attempt_id = a.id AND b.source = 'BATCH'
)
WHERE o.status = 'PAYMENT_PENDING';
```

배치 조회가 회로 열림 등으로 보류되면 해당 시도의 이력이 없을 수 있으므로, 주문 ID의 보류 로그와 이력 시각도 대조한다.

결제 이력(`payment_event`)의 `CREATE_RESPONSE · APPLIED · 관찰 거래 없음`은 생성 응답을 받지 못해 결제 시도를 `UNKNOWN`으로 바꿨다는 기록이다. PG 거래를 관찰했다는 뜻이 아니다. 생성 응답을 받은 뒤 저장에 실패하면 이 이력도 함께 롤백되어 `REQUESTED`에 이력이 없다.

### A-2. 로그 — 어느 경계에서 멈췄나 (예시)

주문 번호 `LP-ORD-<주문 ID 10자리>`, `orderId=<주문 ID>`, 거래 키(`transactionKey=...`)로 함께 검색한다. PG 경계 로그는 주문 번호로, 저장 실패 로그는 `orderId`로만 남고, 거래 키로 조회한 요청 줄에는 주문 번호가 없다. 아래 표는 지금까지 관찰한 예시다. 표에 없는 모양이어도 2절의 질문 순서는 그대로다. 문구 전체는 [결제 로그 지도](../week10/log-map.md)를 본다.

| 보이는 로그 | 뜻 |
| --- | --- |
| `PG 요청: op=create ...` 뒤 `PG 응답 없음: op=create ... error=SocketTimeoutException` (또는 `NoHttpResponseException`) | 생성 응답을 받지 못했다. 응답 유실 후보 |
| `PG 응답: op=create ... httpStatus=202` 뒤 `결제 결과 저장 실패: ... step=TX_B:CREATE_RESPONSE` | 응답은 받았는데 저장에 실패했다. 결제 시도는 `REQUESTED`로 남는다 |
| `PG 응답: op=lookup ... SUCCESS` 뒤 `결제 결과 저장 실패: ... step=TX_B:<경로>` | 조회 결과를 받았는데 저장에 실패했다. DB는 응답 유실과 같아 보인다 |
| `PG 응답: op=lookup ... SUCCESS` 뒤 `결제 결과 저장 실패: ... step=TX_C` | 결과는 저장했는데 주문 반영에 실패했다. 결제 시도는 `SUCCESS`, 주문은 `PAYMENT_PENDING` |
| `PG 응답: op=create ... httpStatus=202 ... PENDING` 뒤 아무 줄 없음 | PG 접수까지 확인했고 callback·조회가 한 번도 오지 않았다. PG가 처리 중인지 callback이 유실됐는지는 로그로 가를 수 없어 진단 조회가 필요하다 |
| `결제 결과 확인 보류: orderId=... cause=CIRCUIT_OPEN` | 조회 회로가 열려 PG에 묻지 못했다 |
| `PG 요청: op=create`만 있고 결과 줄이 없음 | 호출 시작까지만 관찰했다. 응답 대기, 프로세스 종료, 로그 수집 누락을 구분하려면 해당 인스턴스의 종료·수집 기록과 PG 거래를 확인한다. PG 호출 중 서버 중단은 재현하지 않았다 |
| 결제 시도는 `REQUESTED`인데 `PG 요청: op=create` 줄도 없음 | PG 호출 전에 멈췄거나 로그가 수집되지 않았다. 로그만으로는 둘을 가를 수 없다 |

`결제 결과 저장 실패`와 같은 주문·시도의 응답 로그가 함께 있으면 실패한 저장 단계를 특정할 수 있다. 오류 줄을 찾지 못하면 DB의 결제 시도·이력과 PG 거래를 대조한다. 자료가 부족하면 원인을 미확정으로 남긴다. 원인이 미확정이어도 2절의 다음 조치는 정할 수 있다.

### A-3. PG — 진단 조회

같은 PG 계정에서 각 주문 번호로 읽기 전용 조회를 한 번 한다. 내부에 거래 키가 있으면 반환된 키도 대조한다. 이 한도는 이번 실험의 수동 진단 호출 횟수다. 어댑터 내부 재시도는 별도로 로그에 남는다.

callback·배치·이전 reconcile이 이미 한 조회 로그는 **원인을 가르는 근거**로 쓴다. 조치 여부는 그 뒤 상태가 바뀌었을 수 있으므로 이번 진단 조회의 응답으로 정한다.

**거래가 0건이면** PG가 아직 저장하기 전일 수 있다. 결제 시도를 만든 지 10분이 지나도 0건이면 멈춤으로 옮긴다. 요청이 PG에 닿지 않았을 수 있지만, 결제를 다시 요청하지는 않는다. 고객 안내나 주문 처리는 사람이 정한다. 10분은 기존 복구 배치가 미완료 결제 시도를 다시 보는 기준(`PaymentRecoveryBatch.MIN_AGE`)과 같다. 서비스도 조회 0건을 `NO_TRANSACTION_YET`으로 기록하고 상태를 바꾸지 않는다.

### A-4. 사람이 열기 전에 시스템이 하는 일

결과를 모르는 결제가 모두 사람에게 오지는 않는다. 아래 단계가 먼저 처리하고, 끝까지 남은 주문만 이 runbook의 대상이다.

| 단계 | 하는 일 | 끝내는 것 | 남기는 것 |
| --- | --- | --- | --- |
| 결제 요청 안 | PG 생성 응답을 1초 기다린다. 생성 요청은 다시 보내지 않는다 | 응답을 받은 결제 (접수 `PENDING`, 근거 있는 거절) | 응답을 받지 못한 결제 → `UNKNOWN`, 예약 유지 |
| PG callback | callback을 받으면 PG에 다시 조회한다 | 주문 번호·금액이 맞는 `SUCCESS`·`FAILED` | callback이 오지 않은 결제 |
| 복구 배치 | 결제 시도를 만든 지 10분 지났고 주문이 `PAYMENT_PENDING`인 건을 reconcile과 같은 규칙으로 조회한다 | 주문 번호·금액이 맞는 `SUCCESS`·`FAILED` | 아래 표 |

배치가 남기는 주문은 다음 조치가 서로 다르다. 배치 결과의 미완료 수만으로는 가를 수 없고, 주문별 마지막 배치 이력(A-1 질의)을 봐야 한다.

| 배치가 본 것 | 배치 이력 | 다음 조치 |
| --- | --- | --- |
| 거래가 아직 `PENDING` | `APPLIED`, 관찰 `PENDING` (결제 시도가 `PENDING`·거래 키 저장) | **대기.** 다음 회차가 거래 키로 다시 조회한다 |
| 주문 번호는 같고 금액이 다른 거래 | `CONFLICT`, `AMOUNT_MISMATCH` | **멈춤.** 배치가 돌 때마다 같은 충돌을 다시 기록한다 |
| 빈 목록 (예: PG에 보내지 않은 `REQUESTED`) | `IGNORED`, `NO_TRANSACTION_YET` | A-3의 0건 규칙. 10분이 지났으면 멈춤 |
| 조회 실패·회로 열림 | 이력이 없을 수 있다. 보류 로그(`결제 결과 확인 보류`)를 본다 | **대기.** PG·회로가 회복된 뒤 다음 회차를 기다린다 |

- **복구 배치는 기본 설정에서 꺼져 있다.** `payment.recovery.scheduling-enabled=true`인 인스턴스 하나에서만 1분마다 돈다. 꺼져 있으면 callback이 오지 않은 결제는 모두 사람에게 온다.
- 결과를 모르는 결제에 만료 시간은 없다. 시간이 지났다는 이유로 주문을 실패로 바꾸지 않는 대신, 확인될 때까지 예약이 재고를 잡고 있다. PG가 종결 결과를 나중에 바꾸는 경우는 D를 본다.

## B. 재진입 — 조회·저장이 실패했을 때

처음 결제 흐름(생성 응답 저장), callback, 배치, reconcile 중 어디서 실패했든 같은 규칙을 따른다. `step=` 값이 실패한 경로를 알려 준다.

- 조회 보류 로그가 있으면 새 결과가 저장되지 않았다. 해당 주문 상태를 기록하고 회로·PG가 회복된 뒤 2절 2번부터 다시 한다.
- `step=TX_B:...`에서 실패하면 그 결과 저장이 롤백된다. 원인을 해결한 뒤 DB가 아직 미종결인지 확인하고 같은 주문의 reconcile로 PG 결과를 다시 읽는다.
- `step=TX_C`에서 실패하면 TX B가 저장한 종결 결과가 남는다. 주문·예약만 미반영인지 확인하고 원인을 해결한 뒤 같은 주문을 reconcile한다. 서비스는 저장된 결과로 TX C만 다시 실행한다.
- 이미 4절의 완료 조합이면 추가 조치는 끝낸다. 같은 주문을 다시 reconcile해도 PG 생성·조회나 재고 반영이 늘지 않는다.
- 같은 작업 안에서 조회나 reconcile을 반복하지 않는다.

reconcile 동작:

- 주문자 본인으로 호출한다. 다른 사용자로 호출하면 `404 주문을 찾을 수 없습니다.`로 거절된다.
- 내부 결제 시도가 `REQUESTED`·`UNKNOWN`·`PENDING`처럼 종결 전이면 reconcile은 PG를 다시 조회해 서비스가 직접 확인한 결과를 저장하고 주문·예약에 반영한다. 결제를 새로 요청하지 않는다.
- 성공한 주문은 reconcile·callback이 반복돼도 확정·예약 소비·구매 활동이 한 번이다. 실패한 주문은 예약을 한 번만 해제한다.
- 회로가 열려 있으면 reconcile도 조회를 보류한다.

## C. 전체 복구 확인

주문 상태만 보지 않고 같은 주문 집합을 끝까지 맞춘다.

| 같은 주문에서 확인 | PG `SUCCESS` | PG `FAILED` |
| --- | --- | --- |
| 거래·재고 | 결제 시도의 거래 키와 금액이 진단 조회와 일치한다. 예약 때 차감한 수량을 추가 차감하지 않는다 | 거래 키와 **결제 시도 금액**을 대조한다. 주문의 실결제액은 0이고 예약 수량만 한 번 복원한다 |
| 후속 효과 | 같은 주문의 Outbox가 `PUBLISHED`인지 보고, 주문 항목과 구매 활동의 주문 ID·상품 ID·수량·최초 확정 시각을 직접 대조한다. 새 랭킹 revision을 공개하고 이전 공개본의 점수도 확인한다 | 이 주문에서 주문 확정 이벤트와 구매 활동이 생기지 않아야 한다 |

확인 질의 (읽기 전용, `:ids`는 고정한 주문 ID 목록):

```sql
-- 결제 시도: SUCCESS면 거래 키가 진단 조회의 키와 같아야 한다
SELECT order_id, status, transaction_key, amount FROM payment_attempt WHERE order_id IN (:ids);

-- 주문·예약: SUCCESS면 CONFIRMED·CONSUMED, 실결제액 = PG 금액
SELECT id, status, reservation_status, paid_amount, confirmed_at FROM orders WHERE id IN (:ids);

-- 결과 이력: 이번 조치의 출처(RECONCILE)와 결과가 주문마다 남았는지
SELECT a.order_id, e.source, e.observed_transaction_key, e.observed_status, e.observed_amount, e.result, e.reason
FROM payment_event e JOIN payment_attempt a ON a.id = e.attempt_id
WHERE a.order_id IN (:ids) ORDER BY a.order_id, e.id;

-- Outbox: 확정된 주문마다 한 행, PUBLISHED는 발행까지만 뜻한다
SELECT order_id, event_id, status FROM order_outbox WHERE order_id IN (:ids);

-- 구매 활동: 주문 항목과 상품·수량·확정 시각이 한 줄씩 같아야 한다 (행 수만 세지 않는다)
SELECT i.order_id, i.product_id, i.quantity, o.confirmed_at FROM order_item i JOIN orders o ON o.id = i.order_id WHERE i.order_id IN (:ids);
SELECT order_id, product_id, quantity, confirmed_at FROM product_purchase_activity WHERE order_id IN (:ids);
```

두 경로 모두 복구 중 PG 생성 횟수 증가가 0인지 주문별로 확인한다. UNKNOWN gauge는 전체 DB 집계다. 다른 미확인 주문이 있으면 해당 주문을 복구해도 0이 되지 않는다. `NaN`이면 마지막 집계에 사용할 값이 없으므로 DB를 직접 확인한다.

## D. 남은 위험 · 다루지 않는 것

| 위험 | 상태 |
| --- | --- |
| 운영자용 대리 조정 API가 없다. 관리자 주문 API(`/api-admin/v1/orders`)는 조회만 한다 | 운영자가 조치하려면 주문자 헤더로 본인 reconcile을 호출해야 한다. 누가 언제 대신 호출했는지 남는 접근 통제·감사 기록이 없다 |
| 결과를 모르는 결제에 만료가 없어 예약이 계속 재고를 잡는다 | 확인될 때까지 해당 수량은 다른 고객이 살 수 없다. 오래 남은 주문은 주문 기준 경과 시간과 A-1 질의로 찾는다 |
| 금액·주문 번호 충돌 주문도 복구 배치가 돌 때마다 다시 조회하고 같은 충돌 이력·경고를 남긴다 | 두 회차 실험에서 충돌 이력 2건 확인. 배치에서 빼거나 한 번만 알리는 방식은 정하지 않았다 |
| `REQUESTED`에 멈춘 결제는 UNKNOWN gauge에 보이지 않는다 | 주문 기준 경과 시간과 A-1 질의에 보인다 |
| 주문 반영(TX C)에 실패하면 결제 시도는 `SUCCESS`, 주문은 `PAYMENT_PENDING`으로 남는다 | UNKNOWN gauge와 결제 시도 상태 기준 집계에는 보이지 않는다(실험에서 UNKNOWN 0·REQUESTED 0). 주문 기준 경과 시간과 A-1 질의에는 보인다. 같은 주문을 reconcile하면 PG 조회 없이 TX C만 다시 실행했다 |
| DB가 `FAILED`로 끝난 뒤 PG 거래가 `SUCCESS`로 바뀌면 시스템은 다시 보지 않는다 | callback·reconcile·배치 모두 끝난 주문을 다시 조회하지 않았다. 주문이 끝난 상태라 주문 기준 경과 시간에도 보이지 않는다. 이 경우의 절차는 정하지 않았다 |
| 두 gauge는 인스턴스마다 같은 DB 전체를 센다 | 여러 인스턴스를 합치면 중복된다. 대시보드는 `max()`로 본다 |
| 주문 기준 gauge는 `orders.status` 인덱스 없이 집계한다 | 주문이 많아지면 집계마다 주문 테이블을 훑는다 |
| 생성 응답 timeout 1초가 적절한지 | 실제 PG 응답 분포로 확인하지 않았다. 판단에 쓸 관측은 `commerce.pg.call`의 생성 호출 시간 분포와 결과별 비율이다 |
| `PENDING`이 얼마나 오래가면 사람에게 넘길지 상한이 없다 | 0건은 10분 규칙이 있지만 `PENDING`은 다음 회차를 기다린다. 상한은 정하지 않았다 |
| 멈춘 주문을 누구에게 넘길지(에스컬레이션) | 로컬 실험이라 담당자·연락처를 정하지 않았다 |
| 사람이 확인한 뒤의 절차(환불·고객 안내·예약 해제) | 정하지 않았다 |

다루지 않는 것:

- consumer가 멈춰 `order_outbox`는 `PUBLISHED`인데 구매 활동이 없는 경우. 결제 UNKNOWN gauge와 health로는 보이지 않는다. Kafka consumer lag와 "확정됐는데 구매 활동이 없는 주문" DB 대사로 알아챌 수 있으나 이번에 실행하지 않았다.
- 실제 외부 PG 장애, 실계정·실카드, 여러 서버 동시 실행, 부하 상황.

## E. 후속 — 확인할 수 있는 개선

10주차 실험에서 막지 못한 것과, 바꾸면 무엇으로 확인할 수 있는지를 적는다.

| 막지 못한 것 | 확인할 수 있는 개선 |
| --- | --- |
| UNKNOWN 지표만으로는 저장 실패·PG 미완료·주문 반영 실패가 보이지 않았다 | 주문 기준 경과 시간 gauge와 패널을 추가했다(ADR 0101). 정상 흐름에 멈춘 세 주문을 섞어 세 건이 모두 보이는지 실험으로 확인했다 |
| 사람이 대시보드를 보지 않으면 멈춘 주문을 모른다 | 주문 기준 경과 시간이 N초를 넘으면 이 문서로 안내하는 알림. N은 자동 처리가 끝났어야 할 시간보다 길어야 한다. 복구 배치를 켜면 10분(배치 대상 기준) + 1분(배치 주기)보다, 끄면 실제 PG의 callback 도착 시간보다 길게 잡는다. 정상 흐름에서 울리지 않는지, 멈춘 주문 한 건을 넣었을 때 N초 뒤 울리는지로 확인한다 |
| 지표 집계가 멈추면 알림도 조용해진다 | 두 gauge가 `NaN`이거나 수집되지 않으면 울리는 알림 |
| 응답 유실이 생기는 것 자체 | UNKNOWN 수 증가는 자동 처리가 닫을 수 있어 사람을 부르지 않고 주의 단계로 둔다 |
| 끝난 주문의 PG 결과가 나중에 바뀌는 경우(`FAILED` 뒤 `SUCCESS`) | 경과 시간으로는 잡을 수 없다. PG 거래와 내부 결과를 정기적으로 대사한다 |

알림에 넣을 값은 경과 시간, 남은 건수, 이 문서의 링크다. 주문 ID는 넣지 않는다. 지표 label이 끝없이 늘어나고, A-1 질의로 찾을 수 있다. 알림은 아직 만들지 않았다. N을 정할 실제 PG 결과 확정 시간과 고객 대기 허용 시간이 없다.

어디까지 자동으로 끝나는지는 A-4에 있다. 사람이 볼 주문은 금액·주문 번호 충돌, 10분 뒤에도 PG 거래 0건, 끝나지 않는 `PENDING`(상한을 정하면 그 뒤), 끝난 뒤 PG 결과가 바뀐 주문이다.

## F. 근거

| 내용 | 근거 |
| --- | --- |
| health가 `UP`인 동안 세 주문이 확인 중이었다. 운영 중에는 같은 시간대의 5xx 비율을 별도로 확인한다 | D 실험 [가설과 판정](../week10/d-hypotheses.md) |
| 지표 지연: 앱은 집계를 마치고 5초 뒤 DB를 다시 센다. 단건 실험에서 DB 변화부터 endpoint 반영까지 3.081초, 로컬 Prometheus는 5초마다 수집 | [10주차 구현 기록](../week10/implementation-log.md) |
| 응답 유실 주문의 진행 근거 예시 (주문 13) | [조치 직전 기록](../week10/measurements/de-lifecycle-02/fault.txt) |
| 복구 뒤 후속 효과 대조 | [복구 원문](../week10/measurements/de-lifecycle-02/recovery.txt) · [구매 활동·랭킹 원문](../week10/measurements/de-lifecycle-02/ranking.txt) |
| 배치가 남기는 주문: 0건 | `PaymentRecoveryBatchTest.recoversThreeLostResponsesAndLeavesOneUnsentOrderForReview`. UNKNOWN 집계가 0이 된 뒤에도 4번 주문이 `REQUESTED`였고 마지막 배치 이력은 `NO_TRANSACTION_YET` |
| 배치가 남기는 주문: `PENDING`·금액 불일치 | `PaymentRecoveryBatchTest.pendingOrderWaitsAndAmountMismatchStaysForReview` |
| 배치 조회 보류 | `PaymentRecoveryBatchTest` |
| 결제 시도 기준과 주문 기준이 보이는 주문의 차이, 주문별 마지막 로그 줄 | `PaymentStuckSignalExperimentTest`, [10주차 구현 기록 K](../week10/implementation-log.md#k-멈춘-주문을-결제-시도-기준과-주문-기준으로-세어-비교) |
| TX C 실패가 지표·`REQUESTED` 질의에 안 보임 | `PaymentRecoveryBatchTest.orderApplyFailureLeavesSuccessAttemptOutsideCountsAndRetriesWithoutLookup` |
| 종결된 실패 주문은 PG가 바뀌어도 다시 보지 않음 | `PaymentRecoveryBatchTest.finishedFailedOrderIsNotRecheckedWhenPgLaterSaysSuccess` |
| 거래 2건 이상은 자동 적용하지 않음 | `OrderPaymentIntegrationTest` (`MULTIPLE_TRANSACTIONS`). 사고 재현 실험에서는 만들지 않았다 |
| 배치 실험 조건 | 결제 시도의 생성 시각을 11분 전으로 옮겨 한 회차를 실행했다. 실제 10분 대기와 스케줄 시각은 재지 않았다. 배치는 알림을 보내지 않는다 |
| 실패 결과의 예약 해제·재고 복원 | D+E에서 재현한 세 주문은 모두 성공 경로였다. 실패 경로는 기존 `PaymentResultApplicationTest`가 검증한다 |
| 저장과 주문 반영의 경계 | [ADR 0032](../adr/0032-persist-payment-result-before-order-application.md) |
