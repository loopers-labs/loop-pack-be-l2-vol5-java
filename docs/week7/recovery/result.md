# W6 누락 재현과 W7 복구 결과 (EXP-014)

- 실행: `bash docs/week7/recovery/run.sh`, W6 `upstream/seeun0210`(00389e36), W7 `f7bd056e`, 2026-11-06T07:36:28Z
- topic `order-events-recovery-20261106163519`(partition 3), group `purchase-activity-recovery-20261106163519`, MySQL 컨테이너 `loopers-recovery-mysql-20261106163519` 는 실행마다 새로 만들고 실행이 끝나면 지운다. 이전 실행의 DB 를 쓰지 않는다
- W6 api, W6 streamer, PG fixture 를 프로세스로 띄웠다. 주문 B 는 W6 에서 발행과 소비까지 끝냈다. 주문 A 는 W6 api 를 `linger.ms=60000` 으로 다시 띄워 확정한 뒤, record 가 producer 버퍼에 있는 동안 `kill -9` 로 죽였다(2026-11-06T07:36:02Z). 실패 지표에도 남지 않는 누락이다
- 그 뒤 같은 DB 에 W7 api 를 `ddl-auto=update` 로 띄우고 relay 를 켰다. streamer 는 W6 그대로 두었다
- 주문은 코트(3,000) × 2, 셔츠(1,000) × 1 이다. broker 는 `kcat` 으로 읽었다

## 준비: W6 에서 만든 두 주문

| | 주문 A | 주문 B |
|---|---|---|
| orderId | 2 | 1 |
| eventId | `order-confirmed:v1:2` | `order-confirmed:v1:1` |
| broker 의 record 수 | 0 | 1 |

```
id	status	confirmed_at
1	CONFIRMED	2026-11-06T07:35:49.114678Z
2	CONFIRMED	2026-11-06T07:36:02.592722Z
```

```
order_id	product_id	quantity
1	1	2
1	2	1
2	1	2
2	2	1
```

```
order_id	status	provider_order_id	transaction_key
1	SUCCESS	LP-ORD-0000000001	TEST-TX-0001
2	SUCCESS	LP-ORD-0000000002	TEST-TX-0002
```

재고(100 에서 시작, 두 주문이 각각 코트 2, 셔츠 1 을 예약)

```
id	name	quantity
1	코트	96
2	셔츠	98
```

구매 활동(주문 A 는 없어야 한다)

```
id	order_id	product_id	quantity
1	1	1	2
2	1	2	1
```

PG fixture 호출 수(준비 단계 누적. create 가 C, transactions 가 T)

```
create=2 lookup=2 callback=2 transactions=2
```

## W7 로 바꾼 뒤의 테이블

W6 테이블

```
TABLE_NAME
brand
order_confirmation_receipt
order_event_hold
order_item
orders
payment
payment_discrepancy
point_transaction
product
product_like
product_like_count
purchase_activity
user_point
```

W7 api 기동 뒤 테이블(`order_event_outbox` 만 늘어야 한다)

```
TABLE_NAME
brand
order_confirmation_receipt
order_event_hold
order_event_outbox
order_item
orders
payment
payment_discrepancy
point_transaction
product
product_like
product_like_count
purchase_activity
user_point
```

## 복구

| | 주문 A | 주문 B |
|---|---|---|
| 1 회차 | `{"orderId":2,"result":"APPENDED"}` | `{"orderId":1,"result":"APPENDED"}` |
| 2 회차 | `{"orderId":2,"result":"ALREADY_PRESENT"}` | `{"orderId":1,"result":"ALREADY_PRESENT"}` |
| broker 의 record 수(복구 뒤) | 1 | 2 |

outbox

```
event_id	order_id	status	attempt_count	payload
order-confirmed:v1:1	1	PUBLISHED	1	{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-11-06T07:35:49.114678Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
order-confirmed:v1:2	2	PUBLISHED	1	{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:2","orderId":2,"confirmedAt":"2026-11-06T07:36:02.592722Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

주문(확정 시각이 준비 때와 같아야 한다)

```
id	status	confirmed_at
1	CONFIRMED	2026-11-06T07:35:49.114678Z
2	CONFIRMED	2026-11-06T07:36:02.592722Z
```

## 복구 전후 비교

| 항목 | 복구 전 | 복구 뒤 | 비교 |
|---|---|---|---|
| PG fixture 호출 수 | `create=2 lookup=2 callback=2 transactions=2` | `create=2 lookup=2 callback=2 transactions=2` | 같음 |
| 재고 | `id quantity 1 96 2 98` | `id quantity 1 96 2 98` | 같음 |
| 주문 B 의 구매 활동(행 id, 수량, 수정 시각) | `id product_id quantity updated_at 1 1 2 2026-11-06 07:35:53.912371 2 2 1 2026-11-06 07:35:53.913906` | `id product_id quantity updated_at 1 1 2 2026-11-06 07:35:53.912371 2 2 1 2026-11-06 07:35:53.913906` | 같음 |

## streamer: 처리 기록, 구매 활동, 보류

```
event_id	order_id	confirmed_at	items_digest
order-confirmed:v1:1	1	2026-11-06T07:35:49.114678Z	1:2,2:1
order-confirmed:v1:2	2	2026-11-06T07:36:02.592722Z	1:2,2:1
```

```
order_id	product_id	quantity
1	1	2
1	2	1
2	1	2
2	2	1
```

보류 기록 수: 0

## broker (kcat)

```
partition=0 offset=0 key=1
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-11-06T07:35:49.114678Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
partition=0 offset=1 key=1
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-11-06T07:35:49.114678Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
partition=2 offset=0 key=2
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:2","orderId":2,"confirmedAt":"2026-11-06T07:36:02.592722Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

committed offset (TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG)

```
TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG
order-events-recovery-20261106163519 1 - 0 -
order-events-recovery-20261106163519 2 1 1 0
order-events-recovery-20261106163519 0 2 2 0
```

## 운영 SQL (design 7.1)

상태별 건수

```
status	rows_	oldest_created	max_attempts
PUBLISHED	2	2026-11-06 07:36:15.209726	1
```

PENDING 최장 대기 시간

```
longest_pending_wait_s
NULL
```

BLOCKED

```
(없음)
```

PUBLISHED 인데 처리 기록이 없는 사건

```
(없음)
```
