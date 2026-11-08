# due 조회 스캔 측정 결과

`bash docs/week7/due-scan/run.sh` 가 만든다. MySQL 8.0.46. PUBLISHED 1,000,000 행 뒤 구간 R(100,000 행)의 상태를 시나리오마다 바꾼다.

조회: `select * from order_event_outbox where status = 'PENDING' and next_attempt_at <= utc_timestamp(6) order by id limit 100`

| 인덱스 | 시나리오 | 고른 행 | 페이지 접근 | 실행 시간 | 접근 방식 |
|---|---|---|---|---|---|
| `(status, next_attempt_at, id)` | 평상시: PENDING 50 건이 모두 보낼 때가 됨 | 50 | 276 | 2.36 ms | Index range scan using idx_outbox_due |
| `(status, next_attempt_at, id)` | 장애 뒤 밀림: PENDING 10 만 건이 모두 보낼 때가 됨 | 100 | 1091059 | 1110 ms | Index scan using PRIMARY |
| `(status, next_attempt_at, id)` | 장애 중: PENDING 10 만 건이 모두 backoff 중(고를 행 0) | 0 | 23 | 0.0469 ms | Index range scan using idx_outbox_due |
| `(status, next_attempt_at, id)` | 회복 중: 앞쪽 PENDING 99,950 건은 backoff 중, 뒤쪽 50 건만 보낼 때가 됨 | 50 | 277 | 1.31 ms | Index range scan using idx_outbox_due |
| `(status, id, next_attempt_at)` | 평상시: PENDING 50 건이 모두 보낼 때가 됨 | 50 | 176 | 0.952 ms | Index lookup using idx_outbox_due |
| `(status, id, next_attempt_at)` | 장애 뒤 밀림: PENDING 10 만 건이 모두 보낼 때가 됨 | 100 | 339 | 0.301 ms | Index lookup using idx_outbox_due |
| `(status, id, next_attempt_at)` | 장애 중: PENDING 10 만 건이 모두 backoff 중(고를 행 0) | 0 | 214 | 10.9 ms | Index lookup using idx_outbox_due |
| `(status, id, next_attempt_at)` | 회복 중: 앞쪽 PENDING 99,950 건은 backoff 중, 뒤쪽 50 건만 보낼 때가 됨 | 50 | 368 | 13.8 ms | Index lookup using idx_outbox_due |

페이지 접근은 `EXPLAIN ANALYZE` 한 번 동안 늘어난 `Innodb_buffer_pool_read_requests` 에서, 상태만 읽었을 때 늘어난 값을 뺀 것이다. 이 컨테이너에는 다른 부하가 없고, 시나리오의 update 가 남긴 undo 를 purge 가 다 지운 뒤(`trx_rseg_history_len = 0`)에 잰다. 인덱스 조건 푸시다운으로 걸러진 항목은 `Handler_read_*` 에 잡히지 않아 이 값을 쓴다. 실행 시간은 `EXPLAIN ANALYZE` 최상위 노드의 끝 시각이고 buffer pool 상태에 따라 흔들린다.

## 실행 계획

### `(status, next_attempt_at, id)` · steady

```
-> Limit: 100 row(s)  (cost=57.5 rows=50) (actual time=2.32..2.36 rows=50 loops=1)
    -> Sort row IDs: order_event_outbox.id, limit input to 100 row(s) per chunk  (cost=57.5 rows=50) (actual time=2.32..2.36 rows=50 loops=1)
        -> Index range scan on order_event_outbox using idx_outbox_due over (status = 'PENDING' AND next_attempt_at <= '2026-11-08 06:19:45.986103'), with index condition: ((order_event_outbox.`status` = 'PENDING') and (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6))))  (cost=57.5 rows=50) (actual time=0.0335..2.22 rows=50 loops=1)
```

### `(status, next_attempt_at, id)` · backlog

```
-> Limit: 100 row(s)  (cost=65.1 rows=99.9) (actual time=1109..1110 rows=100 loops=1)
    -> Filter: ((order_event_outbox.`status` = 'PENDING') and (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6))))  (cost=65.1 rows=99.9) (actual time=1109..1110 rows=100 loops=1)
        -> Index scan on order_event_outbox using PRIMARY  (cost=65.1 rows=520) (actual time=0.207..1068 rows=1e+6 loops=1)
```

### `(status, next_attempt_at, id)` · outage

```
-> Limit: 100 row(s)  (cost=2.06 rows=1) (actual time=0.0469..0.0469 rows=0 loops=1)
    -> Sort row IDs: order_event_outbox.id, limit input to 100 row(s) per chunk  (cost=2.06 rows=1) (actual time=0.0465..0.0465 rows=0 loops=1)
        -> Index range scan on order_event_outbox using idx_outbox_due over (status = 'PENDING' AND next_attempt_at <= '2026-11-08 06:23:59.515096'), with index condition: ((order_event_outbox.`status` = 'PENDING') and (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6))))  (cost=2.06 rows=1) (actual time=0.0283..0.0283 rows=0 loops=1)
```

### `(status, next_attempt_at, id)` · recovering

```
-> Limit: 100 row(s)  (cost=57.5 rows=50) (actual time=1.27..1.31 rows=50 loops=1)
    -> Sort row IDs: order_event_outbox.id, limit input to 100 row(s) per chunk  (cost=57.5 rows=50) (actual time=1.27..1.31 rows=50 loops=1)
        -> Index range scan on order_event_outbox using idx_outbox_due over (status = 'PENDING' AND next_attempt_at <= '2026-11-08 06:26:04.420064'), with index condition: ((order_event_outbox.`status` = 'PENDING') and (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6))))  (cost=57.5 rows=50) (actual time=0.289..1.11 rows=50 loops=1)
```

### `(status, id, next_attempt_at)` · steady

```
-> Limit: 100 row(s)  (cost=47.9 rows=50) (actual time=0.157..0.952 rows=50 loops=1)
    -> Index lookup on order_event_outbox using idx_outbox_due (status='PENDING'), with index condition: (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6)))  (cost=47.9 rows=50) (actual time=0.125..0.918 rows=50 loops=1)
```

### `(status, id, next_attempt_at)` · backlog

```
-> Limit: 100 row(s)  (cost=101977 rows=100) (actual time=0.0469..0.301 rows=100 loops=1)
    -> Index lookup on order_event_outbox using idx_outbox_due (status='PENDING'), with index condition: (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6)))  (cost=101977 rows=201670) (actual time=0.0436..0.294 rows=100 loops=1)
```

### `(status, id, next_attempt_at)` · outage

```
-> Limit: 100 row(s)  (cost=104546 rows=100) (actual time=10.9..10.9 rows=0 loops=1)
    -> Index lookup on order_event_outbox using idx_outbox_due (status='PENDING'), with index condition: (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6)))  (cost=104546 rows=201670) (actual time=10.9..10.9 rows=0 loops=1)
```

### `(status, id, next_attempt_at)` · recovering

```
-> Limit: 100 row(s)  (cost=104626 rows=100) (actual time=13.6..13.8 rows=50 loops=1)
    -> Index lookup on order_event_outbox using idx_outbox_due (status='PENDING'), with index condition: (order_event_outbox.next_attempt_at <= <cache>(utc_timestamp(6)))  (cost=104626 rows=204240) (actual time=13.6..13.8 rows=50 loops=1)
```

