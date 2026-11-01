```text
key: "42" # orderId
```

payload 형식은 [OrderConfirmed v1 JSON Schema](../../event-contracts/schemas/order/order-confirmed.v1.schema.json)에 정의한다. Kafka topic과 record key는 각 Kafka 어댑터 설정·발행 코드에서 관리하며, record key는 payload와 별도다.

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
