USE loopers;

CREATE TABLE order_event_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    schema_version INT NOT NULL,
    destination_topic VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_key VARCHAR(40) NOT NULL,
    payload LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status VARCHAR(12) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    published_topic VARCHAR(249) NULL,
    published_partition INT NULL,
    published_offset BIGINT NULL,
    last_error VARCHAR(80) NULL,
    INDEX ix_order_outbox_due (status, next_attempt_at, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
