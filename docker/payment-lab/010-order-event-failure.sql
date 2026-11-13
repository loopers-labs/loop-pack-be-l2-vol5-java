USE loopers;

CREATE TABLE order_event_failures (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    consumer_group VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    source_topic VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_partition INT NOT NULL,
    source_offset BIGINT NOT NULL,
    message_key LONGTEXT NULL,
    payload LONGBLOB NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(40) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_order_failure_source UNIQUE (consumer_group, source_topic, source_partition, source_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
