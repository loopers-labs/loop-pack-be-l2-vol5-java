# 결제 확인 중 주문·UNKNOWN 증가 대응

## 1. 증상 확인

주문이 **결제 확인 중(PAYMENT_PENDING)**에 머물거나 `commerce_payment_unknown`이 증가하면 시작한다. health가 UP이어도 주문이 완료됐다는 뜻은 아니다. 정상 결제 진행 중에도 UNKNOWN이 잠시 나타날 수 있으므로, 지표 증가만으로 사고 원인을 확정하지 않는다.

## 2. 원인 진단

내부 주문·결제 상태와 PG 거래를 대조한다. `<orderId>`는 실제 대상 ID로 바꾼다.

**내부 주문·결제 상태 조회**

```sql
SELECT o.id, o.user_id, o.status AS order_status, o.paid_amount, o.confirmed_at,
       p.id AS payment_id, p.attempt_key, p.status AS payment_status, p.provider_account,
       p.provider_order_id, p.transaction_key, p.amount, p.failure_kind,
       p.verification_status, p.verification_checked_at
FROM orders o LEFT JOIN payment p ON p.order_id = o.id
WHERE o.id = <orderId>;
SELECT c.payment_id, c.code, c.observed_order_id, c.observed_transaction_key,
       c.observed_amount, c.observed_status, c.observed_count, c.detected_at
FROM payment_conflict c JOIN payment p ON p.id = c.payment_id
WHERE p.order_id = <orderId>;
```

**PG 거래 상태 조회** — 위 조회 결과의 `provider_account`와 `provider_order_id`를 사용한다.

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 --get \
  --header 'X-USER-ID: <provider_account>' \
  --data-urlencode 'orderId=<provider_order_id>' \
  '<PG 주소>/api/v1/payments'
```

내부 `transaction_key`가 있으면 기존 reconcile과 같은 키 조회를 사용한다. 아래 응답은 배열이 아닌 단일 거래 객체다.

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 \
  --header 'X-USER-ID: <provider_account>' \
  '<PG 주소>/api/v1/payments/<transaction_key>'
```

PG 조회 응답을 보존하고, `orderId`·`amount`·`transactionKey`를 내부 결제 행과 대조한다. 내부 거래 키가 아직 없으면 PG 거래 키를 기록해 복구 후 같은 키가 저장됐는지 확인한다.

위의 내부 주문·결제 상태 조회 SQL을 다시 실행해 조회 전과 상태가 같은지 확인한다. 달라졌다면 callback 등 다른 처리가 있었을 수 있으므로 현재 상태로 다시 진단한다. 상태 변화만으로 PG 조회가 내부 상태를 바꿨다고 단정하지 않는다.

이어서 **commerce-api의 PG·결제 처리 진단 로그**에서 같은 주문의 아래 항목을 확인한다.

|로그 항목|확인할 내용|
|---|---|
|`PAYMENT_PG_START`·`PAYMENT_PG_RESULT`·`PAYMENT_PG_ERROR`|대상 호출과 결과가 한 쌍인지, 성공 응답·Unavailable·호출 예외 중 무엇인지|
|`TX_METHOD_ENTER`|같은 주문의 APPLY_OBSERVATION과 source를 확인해 반영 시도를 선택|
|`PAYMENT_APPLY_INPUT`·`PAYMENT_APPLY_DECISION`|그 결과를 내부 결제 처리에 어떻게 전달하고 판단했는지|
|`TX_COMPLETION`·`TX_OBSERVATION_UNAVAILABLE`|COMMITTED·ROLLED_BACK·UNKNOWN과 TX 관측 실패 여부|

내부 결제 상태, PG 조회 응답, 위 로그를 함께 보고 다음 행동을 결정한다. 호출/TX 오류나 기록 부족·모순이 있으면 복구 허용 조건보다 우선해 중단한다. 예약·반환·Outbox는 부록 E.1·E.4로 조회한다. PG 측 응답 유실·수신 횟수의 확인 출처와 로그 연결 규칙은 E.3을 따른다.

|DB·PG·로그 대조 결과|다음 행동|
|---|---|
|내부 CONFIRMED·SUCCESS|추가 복구 없이 4번 ‘완료 확인’ 진행|
|PG 단일 SUCCESS·주문 PAYMENT_PENDING·결제 UNKNOWN, CREATE 결과 Unavailable·UNAVAILABLE 반영 COMMITTED, 예약 유지·반환0·Outbox0, PG 응답 유실 경계 기록 일치|응답 유실을 지지. 3번 ‘미확정 주문에 PG 결제 결과 반영’의 조건 확인|
|내부 PENDING·PG SUCCESS|응답 유실로 단정하지 않는다. callback·조회 결과의 반영 여부와 현재 예약·Outbox를 조사한 뒤 재진단한다. 이 문서의 UNKNOWN 복구로 바로 진행하지 않는다.|
|PG PENDING|성공 반영을 보류하고 거래 상태·조회 시각을 기록한다. 정해진 관찰 시간 안에서 다시 조회하고, 최종 결과가 나오면 2번 ‘원인 진단’을 다시 진행한다. 계속 PENDING이면 PG 처리 지연 원인을 조사한다.|
|성공 입력의 내부 TX ROLLED_BACK|응답 유실 복구로 진행하지 않는다. 해당 TX의 예외 로그와 주문·예약·Outbox 상태를 보존하고 내부 저장 실패 원인을 조사한다. 원인 조치 후 2번 ‘원인 진단’부터 현재 상태를 다시 확인한다.|
|PG 조회 실패·FAILED·거래 없음/복수·금액 불일치, 로그 누락·모순·진단 오류|성공 반영을 중단한다. 조회 실패는 접속 대상·계정·외부 주문 ID와 오류 응답을 확인하고, FAILED는 실패 거래와 내부 상태를 대조한다. 거래 불일치는 저장된 계정·주문·금액을 재대사하고, 로그 부족은 해당 주문의 호출·TX 기록을 확보한다. 원인이나 근거가 보완된 뒤 2번 ‘원인 진단’을 다시 진행한다.|
|PAYMENT_PG_ERROR, TX 종료 UNKNOWN, TX_OBSERVATION_UNAVAILABLE|호출·TX 예외와 현재 DB 상태를 보존한다. PG 호출 실패 또는 TX 결과 미확정 원인을 조사하고 근거를 확보한 뒤 재진단한다.|
|그 밖의 조합·필수 근거 확인 불가|원인 미확정으로 남기고 결과 반영을 중단한다. 빠진 조회·호출·TX 기록을 확보한 뒤 재진단한다.|

PENDING 재조회는 시작 전에 관찰 종료 시각과 최대 조회 횟수를 정하고 그 한도 안에서만 수행한다. 한도에 도달하면 상태를 보존하고 미해결로 기록한다. 재조회와 원인 조사 중에도 새 결제를 만들거나 내부 상태를 강제로 바꾸지 않는다.

## 3. 미확정 주문에 PG 결제 결과 반영

PG에서는 성공했지만 내부 결제가 UNKNOWN으로 남은 주문에 **기존 거래를 다시 조회해 현재 결과를 반영한다.** 해당 주문의 reconcile API를 사용한다.

**① 복구 전 확인**

2번 ‘원인 진단’에서 응답 유실을 지지한 주문만 대상으로 한다. 주문·소유자·시도·PG 계정·외부 주문 ID·금액과 복구 전 DB·PG 응답·health/지표·로그·판정 기록을 보존한다.

같은 DB·PG 거래가 유지되는지 확인하고 새 결제 입력과 병행 조정을 멈춘다. 제어된 재현에서는 해당 실행의 응답 유실 주입이 소비/해제되고 진행 중인 PG 요청·콜백이 없는지 pg-state·plan-ledger로 확인한다. 이는 fixture 제어이며 일반 PG에 같은 제어 API가 있다는 뜻은 아니다. 필요한 거래·요청 기록을 확보할 수 없으면 확인 불가로 중단한다.

**② 기존 거래 결과 반영 요청**

2번에서 확인한 주문 ID와 주문 소유자 ID로 아래 요청을 한 번 실행한다. 이 API는 기존 PG 거래를 다시 조회해 **현재 조회 결과**를 내부에 반영한다. 진단 때 SUCCESS였어도 호출 시점의 결과가 다를 수 있다. `--include`로 HTTP 상태코드와 응답 본문을 함께 확인한다.

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 --request POST \
  --header 'X-USER-ID: <주문 소유자 ID>' \
  '<업무 API 주소>/api/v1/orders/<orderId>/payment/reconcile'
```

**③ 응답에 따라 다음 행동 결정**

|요청 결과|다음 행동|
|---|---|
|HTTP 200, `data.id`가 대상 주문, `data.status=CONFIRMED`·`data.payment.status=SUCCESS`|내부 확정 응답을 받았다. 4번 ‘완료 확인’에서 같은 거래 반영·중복 효과·후속 처리를 검증한다.|
|HTTP 200·PAYMENT_FAILED|PG 실패 결과가 반영돼 예약이 반환될 수 있다. 실패 원인·반환 이력·현재 상태를 보존하고 성공 복구로 처리하지 않는다.|
|HTTP 202·미확정|조회 불가·PENDING 등 실제 응답과 verification 상태/시각을 보존하고 중단한다. 진단용 PG 읽기와 달리 reconcile은 검증 기록을 바꿀 수 있다.|
|HTTP 409|거래 불일치/복수 등 충돌 응답과 payment_conflict·verification 기록을 대조하고 중단한다.|
|그 밖의 오류|응답과 현재 상태를 보존하고 중단한다. 소유자·접속 대상·오류 원인을 확인한다.|
|통신 실패·시간 초과로 결과를 받지 못함|처리가 안 됐다고 단정하지 않는다. 즉시 재호출하지 말고 2번 ‘원인 진단’부터 현재 DB·PG 상태를 다시 확인한다.|

**주의:** 새 결제 생성·confirm 재호출·DB 강제 변경으로 대신하지 않는다.

## 4. 완료 확인

복구 응답을 받은 뒤 아래 순서로 실제 결과를 확인한다. 복구 전 기록과 **같은 주문·시도·거래**를 대조한다.

**① 주문·PG 거래 확인**

내부 상태 조회 SQL과 PG 거래 조회를 다시 실행한다. 같은 orderId·attemptKey의 주문이 CONFIRMED, 결제가 SUCCESS이고, paidAmount가 저장금액과 같은지 확인한다. 내부 transactionKey는 진단 때 기록한 PG 성공 거래 키와 같아야 하며, PG 거래 집합도 그대로여야 한다.

- 일치하면 ②로 진행한다.
- 미확정이면 결과를 보존하고 2번 ‘원인 진단’부터 현재 상태를 다시 확인한다.
- 주문·거래·금액이 다르면 복구 미완료로 기록하고, 저장 값과 PG 거래의 불일치 원인을 조사한다. 바로 재호출하지 않는다.

**② 추가 결제·부수 효과 확인**

PG 수신 기록에서 복구 전후 CREATE 증가가 0인지 확인한다. 제출한 재현은 `fault/pg-state.json`과 `recovery/pg-state.json`의 counts.createReceived 및 journal을 비교한다(출처 E.3). PG 조회 응답이 같다는 것만으로 추가 결제 시도가 없었다고 판단하지 않는다.

재고·예약·포인트·쿠폰을 복구 전 값과 대조한다. 추가 재고 차감·예약 반환이 없어야 하고 포인트·쿠폰은 변하지 않아야 한다. 이미 저장된 confirmedAt은 보존돼야 한다. 처음 확정되는 주문에는 최초 확정 시각이 생긴다. 상세 조회 SQL은 부록 E.1에 있다.

- 모두 일치하면 ③으로 진행한다.
- 추가 CREATE나 예상 밖의 상태 변화가 있으면 추가 복구 조치를 멈추고 해당 호출·주문별 변경 이력을 조사한다.
- 비교할 기록이 없으면 확인 미완료로 남긴다. 이상이 없었다고 처리하지 않는다.

**③ 후속 처리·관측·기록 확인**

Outbox 발행, 주문 품목별 구매 활동이 한 번씩 저장됐는지와 수량, 새 주간 snapshot과 공개 랭킹 API의 점수·순위를 차례로 확인한다. 같은 주의 정상 주문도 기대 점수에 포함한다. 상세 조회는 부록 E.4를 사용한다.

- Outbox가 없거나 미발행이면 eventId·저장 상태·발행 오류를 확인한다.
- 발행됐지만 활동이 누락/중복됐으면 해당 이벤트의 전달·소비 기록을 조사한다.
- 활동은 맞지만 랭킹이 다르면 집계 입력·대상 주·snapshot·공개 결과를 대조한다.

후속 처리가 빠졌으면 **주문 복구 완료·후속 미완료**로 구분하고, 결제 복구를 반복하지 않는다.

마지막으로 대상 결제가 UNKNOWN에서 해소됐는지, 실제 health·업무 지표 응답과 복구 전 원문 hash가 보존됐는지 확인한다. 다른 UNKNOWN 주문이 남아 있으면 전체 지표가 0이 아닐 수 있으므로 대상 주문의 완료와 구분한다. 관측 실패·hash 불일치는 확인/보존 실패로 기록한다.

**종료 기록:** 대상별로 `원인 미확정·중단` / `복구 미완료` / `주문 복구 완료·후속 미완료` / `후속까지 확인 완료`를 구분하고 시각·직전 응답·확인하지 못한 항목·남은 문제를 남긴다. 일부만 끝났으면 완료/미완료 대상을 나눈다. 후속 결과가 맞아도 관측·보존 실패를 숨기지 않는다.

**재진단:** 상태가 남아 있으면 같은 주문을 2번 ‘원인 진단’부터 다시 확인한다. 이미 확정됐으면 후속 처리만 확인한다. 상태가 사라졌으면 이전 주문을 복구했다고 기록하지 않는다. 새 재현과 같은 주문의 재진단은 구분한다.

---

## 부록 E. 참고 조회 명령

`<...>`는 대상 환경과 실제 식별자로 바꾼다. 조회 결과는 복구 전후 별도로 보존한다.

### E.1 예약·재고·포인트·쿠폰

대상을 모르면 UNKNOWN 목록을 조회한다. 주문·결제 상세는 본문의 SQL을 사용한다.

```sql
SELECT order_id, status, provider_order_id, verification_checked_at
FROM payment WHERE status = 'UNKNOWN' ORDER BY order_id;
```

아래 SQL과 E.4의 주문별 조회는 같은 DB 세션에서 실행한다.

```sql
SET @incident_order_id = <대상 주문 ID>;
SET @incident_user_id = (SELECT user_id FROM orders WHERE id = @incident_order_id);

SELECT order_id, product_id, cause, changed_quantity,
       before_quantity, after_quantity
FROM stock_history WHERE order_id = @incident_order_id ORDER BY product_id, id;
SELECT p.id, p.stock_quantity FROM product p
JOIN order_item i ON i.product_id = p.id
WHERE i.order_id = @incident_order_id ORDER BY p.id;

SELECT * FROM point WHERE user_id = @incident_user_id ORDER BY id;
SELECT h.* FROM point_history h JOIN point p ON p.id = h.point_id
WHERE p.user_id = @incident_user_id ORDER BY h.id;
SELECT * FROM coupon WHERE user_id = @incident_user_id ORDER BY id;
SELECT DISTINCT c.* FROM coupon_campaign c JOIN coupon k ON k.campaign_id = c.id
WHERE k.user_id = @incident_user_id ORDER BY c.id;
```

예약의 `changed_quantity`는 변경량의 크기다. 감소 방향은 `before_quantity-after_quantity`로 확인한다. 같은 사용자·상품의 다른 활동이 있었다면 주문별 이력과 구분한다.

### E.2 health·업무 지표

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 \
  '<management 주소>/actuator/health'
curl --include --silent --show-error --connect-timeout 3 --max-time 30 \
  '<management 주소>/actuator/prometheus'
```

HTTP 상태·health의 status·`commerce_payment_unknown` 값과 수신 시각을 남긴다. HTTP 오류·누락·NaN은 0으로 바꾸지 않는다. 지표는 전체 DB의 UNKNOWN을 세고 PENDING은 제외한다.

### E.3 로그 연결·원문 보존

```bash
rg -F '<provider_order_id>' '<commerce-api 진단 로그 파일>'
rg -F '"orderId":<orderId>,' '<commerce-api 진단 로그 파일>'
rg -F '<대상 CREATE의 callId>' '<commerce-api 진단 로그 파일>'
rg -F '<대상 APPLY의 observationId>' '<commerce-api 진단 로그 파일>'
```

대상 시각 구간에서 같은 providerOrderId의 CREATE START와 같은 callId의 RESULT가 각각 1개인지 확인한다. 같은 주문의 `TX_METHOD_ENTER` 중 `operation=APPLY_OBSERVATION`·`source=CREATE`인 행이 정확히 1개이고 RESULT 다음에 나오는지 확인한 뒤 그 observationId로 INPUT·DECISION·TX_COMPLETION을 연결한다. 다른 source의 반영도 함께 있거나 대응이 누락/복수이면 원인 미확정으로 중단한다. `PAYMENT_CONTEXT`만 보고 observationId를 선택하지 않는다.

재현의 PG 측 경계·CREATE 수신 횟수는 [제출 원문](../verification/evidence/20261126T090053Z-558297c0/manifest.json)의 `fault/pg-state.json`·`recovery/pg-state.json`에서 확인한다. journal의 createdTransactionKey·CLOSED_EARLY, counts.createReceived, activeRequests·activeCallbackJobs를 대조한다. 이 값은 테스트 fixture의 기록이며 PG 거래 조회 API가 반환하는 필드는 아니다. 다른 환경에서는 대상 주문과 수신 시도를 식별할 수 있는 PG 측 요청 기록이 필요하다. 거래 생성 성공 로그 줄 수만으로 모든 CREATE 수신 횟수를 대신 세지 않는다. 출처가 없으면 원인/추가 호출 여부를 확인했다고 기록하지 않는다.

실제 운영 환경에서 이 수동 절차를 실행 검증한 것은 아니며, 제어된 사고 실행의 증거와 기존 API/DB 계약을 기준으로 작성했다.

복구 전 DB·PG 응답·health/지표·로그와 가설별 예상·실제·판정을 보존한다. 원문 파일만 둔 디렉터리의 hash를 만들고, hash 목록은 그 디렉터리 밖에 둔다. 기록이나 hash 생성에 실패하면 복구를 중단한다.

```bash
shasum -a 256 '<복구 전 기록 디렉터리>'/* > '<hash 목록 파일>'
```

복구 후에는 원문을 덮지 않고 아래로 모두 OK인지 확인한다. hash 생성 때와 같은 작업 위치에서 실행한다.

```bash
shasum -a 256 -c '<hash 목록 파일>'
```

### E.4 후속 처리

```sql
SELECT product_id, quantity FROM order_item
WHERE order_id = @incident_order_id ORDER BY product_id;
SELECT event_id, status, attempt_count, published_at, next_attempt_at, last_error
FROM order_confirmed_outbox WHERE order_id = @incident_order_id;
SELECT order_id, product_id, quantity, confirmed_at
FROM product_purchase_activity WHERE order_id = @incident_order_id ORDER BY product_id;
```

Outbox PENDING은 next_attempt_at·last_error를, BLOCKED는 차단 원인을 확인하고 자동 재시도로 해결됐다고 처리하지 않는다.

streamer의 `ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED`에서 eventId·topic·partition·offset·APPLIED/ALREADY_PROCESSED를 확인하고 group commit과 연결한다.

대상 confirmedAt이 속한 주의 **복구 후 활동을 포함하는 새 snapshot**을 사용한다. 제어된 재현에서는 연결 테스트가 기존 batch 자식의 startNew를 실행했고, 제출 원문의 `recovery/ranking.json`에 snapshotId·실행/공개 결과가 있다. 종료돼 DB가 정리된 실행은 이 기록을 확인한다.

상태가 남은 환경에서 새 집계가 필요하면 배치 실행 담당자가 같은 DB의 구매 활동과 Batch schema가 준비됐는지 확인한 뒤, 아래 기존 명시 실행기로 해당 주의 새 revision을 만든다. 같은 기간의 다른 실행이 없을 때 수행하고, 기존 schema/활동/공개 snapshot을 초기화하지 않는다.

```bash
java -jar '<commerce-batch 실행 JAR>' --spring.profiles.active=local \
  --spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false \
  --spring.jpa.hibernate.ddl-auto=none --spring.batch.jdbc.initialize-schema=never \
  --weekly-ranking.launch.enabled=true --weekly-ranking.mode=new \
  --weekly-ranking.period-start='<해당 주 월요일 YYYY-MM-DD>'
```

실행 로그의 `weeklyRanking new ... snapshotId=...`에서 새 ID를 얻는다. 실행 실패·미공개이면 후속 미완료로 남기고 재실행/기존 snapshot 변경을 임의로 하지 않는다. ID를 확인할 때는 다음처럼 대상 주의 snapshot을 조회할 수도 있다.

```sql
SELECT snapshot_id, status, input_count, period_start, period_end
FROM ranking_snapshot WHERE period_start = '<해당 주 월요일 YYYY-MM-DD>';
```

아래 `<snapshot ID>`에는 새 실행의 ID를 넣는다. 공개 API의 snapshotId도 이 ID와 같아야 하며, 이전 공개 ID를 새 결과로 오인하지 않는다.

```sql
SELECT snapshot_id, status, input_count, period_start, period_end
FROM ranking_snapshot WHERE snapshot_id = '<snapshot ID>';
SELECT product_id, score, calculated_rank
FROM ranking_result WHERE snapshot_id = '<snapshot ID>' ORDER BY calculated_rank;
```

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 --get \
  --header 'X-USER-ID: <주문 소유자 ID>' \
  --data-urlencode 'periodStart=<해당 주 월요일 YYYY-MM-DD>' \
  '<업무 API 주소>/api/v1/rankings/weekly'
```

snapshot COMPLETED·API PUBLISHED·같은 snapshotId·기대 점수/순위와 일치하는지 확인한다.
