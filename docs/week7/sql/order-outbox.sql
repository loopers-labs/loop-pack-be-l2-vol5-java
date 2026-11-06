-- 기존 주문·결제 스키마와 같은 MySQL 데이터베이스에 적용한다.
CREATE TABLE IF NOT EXISTS order_outbox (
    event_id VARCHAR(160) NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version INT NOT NULL,
    message_key VARCHAR(80) NOT NULL,
    destination_topic VARCHAR(249) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    cycle_attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6),
    last_error VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    KEY idx_order_outbox_pending (status, next_attempt_at, created_at)
);
