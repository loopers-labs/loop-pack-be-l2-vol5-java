# W6 누락 재현 결과 (EXP-007 · EXP-008)

- 실행: `bash docs/week6/gap/run.sh` · 이 문서가 든 커밋의 코드 · 2026-10-30T07:03:20Z
- topic `order-events-gap-20261030160234`(partition 3) · group `purchase-activity-gap-20261030160234` · MySQL 컨테이너 `loopers-gap-mysql-20261030160234` — 실행마다 새로 만들고 실행 끝에 지운다
- 두 앱과 PG fixture 를 프로세스로 띄웠다. 주문 A 는 topic 의 `max.message.bytes=1` 로 broker 가 거절하게 하고, 되돌린 뒤 주문 B 를 확정했다. 주문 C 는 api 를 `linger.ms=60000` 으로 다시 띄워 확정한 뒤, record 가 producer 버퍼에 있는 동안 `kill -9` 로 api 를 죽였다(2026-10-30T07:03:13Z)
- 주문은 코트(3,000) × 2 · 셔츠(1,000) × 1 이다. 확정 시각은 고정하지 않은 실제 시각이다 — 결제 API 가 `Instant.now()` 를 쓴다
- broker 는 우리 코드가 아니라 `kcat` 으로 읽었다. committed offset 은 `kafka-consumer-groups.sh` 로 읽었다(kcat 은 group offset 을 보여 주지 않는다)

## 주문 A (1) · 주문 B (2) · 주문 C (3)

```
id	status	total_amount	paid_amount	confirmed_at
1	CONFIRMED	7000	7000	2026-10-30T07:02:57.901092Z
2	CONFIRMED	7000	7000	2026-10-30T07:03:01.072818Z
3	CONFIRMED	7000	7000	2026-10-30T07:03:12.967567Z
```

결제 · PG 거래

```
order_id	status	provider_order_id	transaction_key
1	SUCCESS	LP-ORD-0000000001	TEST-TX-0001
2	SUCCESS	LP-ORD-0000000002	TEST-TX-0002
3	SUCCESS	LP-ORD-0000000003	TEST-TX-0003
```

재고(100 에서 시작, 세 주문이 각각 코트 2 · 셔츠 1 을 소비)

```
id	name	quantity
1	코트	94
2	셔츠	97
```

## broker — kcat

```
partition=2 offset=0 key=2 headers=traceparent=00-6ae4412595d8cfd78fc8cb09d1adab99-73c0272855c99b8f-00
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:2","orderId":2,"confirmedAt":"2026-10-30T07:03:01.072818Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

## committed offset — kafka-consumer-groups.sh (TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG)

```
TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG
order-events-gap-20261030160234 0 - 0 -
order-events-gap-20261030160234 2 1 1 0
order-events-gap-20261030160234 1 - 0 -
```

## streamer — 처리 기록 · 구매 활동 · 보류

```
event_id	order_id	confirmed_at	items_digest
order-confirmed:v1:2	2	2026-10-30T07:03:01.072818Z	1:2,2:1
```

```
order_id	product_id	quantity
2	1	2
2	2	1
```

```
holds
0
```

## 지표

```
order_event_publish_total{application="commerce-api",cause="RecordTooLargeException",result="failure",type="OrderConfirmed"} 1.0
order_event_publish_total{application="commerce-api",cause="none",result="success",type="OrderConfirmed"} 1.0
purchase_activity_total{application="commerce-streamer",outcome="recorded",reason="none"} 1.0
```

주문 A 의 발행 실패 로그(api)

```
2026-10-30T16:02:57.988+09:00 ERROR 81750 --- [commerce-api] [ commerce-api-1] [6ae44121e6ebd612a570820f686dd451-20094f43c1cb3021] c.l.i.order.OrderEventKafkaPublisher     : 주문 이벤트를 보내지 못했다. eventId 로 다시 보내야 한다: eventId=order-confirmed:v1:1, topic=order-events-gap-20261030160234
```

## 주문 C — 흔적 없는 누락

kill -9 직전, 다시 띄운 api 의 발행 지표

```
(order_event_publish_total 없음 — 성공도 실패도 세지 않았다)
```

다시 띄운 api 의 발행 로그(성공 · 실패)

```
(없음)
```
