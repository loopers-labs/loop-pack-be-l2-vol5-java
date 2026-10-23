# Local Test PG

`test-pg` is a separate WireMock-based HTTP app for the commerce payment exercise. It keeps synthetic transactions in process memory and does not connect to a real PG or store card data.

Run it with Docker Compose from the repository root:

```bash
docker compose -f docker/pg-compose.yml up --build -d
```

The local base URL is `http://127.0.0.1:18089`:

- `POST /v1/payments/confirm` approves a payment. Send the Toss-compatible `paymentKey`, `orderId`, and `amount` body, Basic authentication, and `Idempotency-Key` header.
- `POST /v1/payments/{paymentKey}/cancel` cancels an approved payment. Send Basic authentication, an `Idempotency-Key` header, and `{"cancelReason":"..."}`. Omitting `cancelAmount` requests a full cancellation.
- `GET /v1/payments/{paymentKey}` returns one stored transaction.
- `GET /v1/payments/orders/{orderId}` returns the transactions for an order.

To exercise a cancellation server error, create a payment with `paymentKey` set to
`test-payment-cancel-server-error`, then call its cancellation endpoint. It returns the Toss-style
`500 FAILED_INTERNAL_SYSTEM_PROCESSING` response and leaves the stored transaction unchanged.

Lookup requests require `X-USER-ID: 135135`.

At startup, the process seeds four lookup examples in memory: `local-txn-success-1` and
`local-txn-success-2` have status `SUCCESS`; `local-txn-failed-1` and `local-txn-failed-2`
have status `FAILED`. Query each one with `GET /v1/payments/{paymentKey}`.

For local scenario control, `POST /__test/payments/status` accepts `transactionKey` and `status` (`SUCCESS` or `FAILED`) and changes a pending transaction. A terminal status cannot be changed. This control endpoint is separate from the PG contract.

Transaction state is saved to `build/test-pg/transactions.json` by default. Docker Compose mounts the same host directory at `/data` and uses `/data/transactions.json`, so generated transactions and their idempotency responses survive `test-pg` restarts. Commerce application restarts do not clear this data. To reset the local PG state, stop the container and remove `build/test-pg/transactions.json`.
