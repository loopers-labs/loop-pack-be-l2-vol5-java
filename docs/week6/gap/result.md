# W6 누락 재현 결과 (EXP-007 · EXP-008)

- 실행: `bash docs/week6/gap/run.sh` · commit `42426d3d` · 2026-10-30T03:33:18Z
- topic `order-events-gap-20261030123238`(partition 3) · group `purchase-activity-gap-20261030123238` · MySQL 컨테이너 `loopers-gap-mysql-20261030123238` — 실행마다 새로 만들고 실행 끝에 지운다
- 두 앱과 PG fixture 를 프로세스로 띄웠다. 주문 A 는 topic 의 `max.message.bytes=1` 로 broker 가 거절하게 하고, 되돌린 뒤 주문 B 를 확정했다
- 주문은 코트(3,000) × 2 · 셔츠(1,000) × 1 이다. 확정 시각은 고정하지 않은 실제 시각이다 — 결제 API 가 `Instant.now()` 를 쓴다
- broker 는 우리 코드가 아니라 `kcat` 으로 읽었다. committed offset 은 `kafka-consumer-groups.sh` 로 읽었다(kcat 은 group offset 을 보여 주지 않는다)

## 주문 A (1) · 주문 B (2)

```
id	status	total_amount	paid_amount	confirmed_at
1	CONFIRMED	7000	7000	2026-10-30T03:33:07.656600Z
2	CONFIRMED	7000	7000	2026-10-30T03:33:10.969453Z
```

결제 · PG 거래

```
order_id	status	provider_order_id	transaction_key
1	SUCCESS	LP-ORD-0000000001	TEST-TX-0001
2	SUCCESS	LP-ORD-0000000002	TEST-TX-0002
```

재고(100 에서 시작, 두 주문이 각각 코트 2 · 셔츠 1 을 소비)

```
id	name	quantity
1	코트	96
2	셔츠	98
```

## broker — kcat

```
partition=2 offset=0 key=2 headers=eventType=OrderConfirmed,schemaVersion=1,eventId=order-confirmed:v1:2,traceparent=00-6ae40ff67ab063e415af643cf07fbefc-5397772267211b1b-00
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:2","orderId":2,"confirmedAt":"2026-10-30T03:33:10.969453Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

## committed offset — kafka-consumer-groups.sh (TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG)

```
TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG
order-events-gap-20261030123238 0 - 0 -
order-events-gap-20261030123238 1 - 0 -
order-events-gap-20261030123238 2 1 1 0
```

## streamer — 처리 기록 · 구매 활동 · 보류

```
event_id	order_id	confirmed_at	items_digest
order-confirmed:v1:2	2	2026-10-30T03:33:10.969453Z	1:2,2:1
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
2026-10-30T12:33:07.764+09:00 ERROR 39754 --- [commerce-api] [ commerce-api-1] [6ae40ff3078bfd215aea067b753df7ff-26b1fc1e673ddcc1] c.l.i.order.OrderEventKafkaPublisher     : 주문 이벤트를 보내지 못했다. eventId 로 다시 보내야 한다: eventId=order-confirmed:v1:1, topic=order-events-gap-20261030123238
```
