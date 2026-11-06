CREATE TABLE order_confirmed_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(150) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_confirmed_outbox_event_id (event_id),
    KEY idx_order_confirmed_outbox_relay (status, next_attempt_at, created_at, id)
);
