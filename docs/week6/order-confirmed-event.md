```text
key: "42" # orderId
```

topic·key·payload schema 경로는 [OrderConfirmed v1 계약](../../contracts/events/order/order-confirmed-v1.contract.json)에, payload 형식은 계약에서 참조하는 JSON Schema에 정의한다. Kafka record key는 payload와 별도다.

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:42",
  "orderId": 42,
  "confirmedAt": "2026-09-07T01:00:00Z",
  "items": [
    { "productId": 7, "quantity": 2 },
    { "productId": 8, "quantity": 1 }
  ]
}
```
