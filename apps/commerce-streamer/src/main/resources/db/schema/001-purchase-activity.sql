-- Streamer-owned historical facts. Do not apply automatically to a user's database.
-- Seconds+nanos preserve the exact Instant; DATETIME(6) would silently discard sub-microsecond differences.
-- Identifiers use a binary NO PAD collation, matching Java String equality including trailing spaces.
CREATE TABLE processed_order_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    schema_version INT NOT NULL,
    confirmed_at_second BIGINT NOT NULL,
    confirmed_at_nano INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_order_confirmation_event_id (event_id),
    UNIQUE KEY uk_processed_order_confirmation_order_id (order_id),
    CONSTRAINT ck_confirmation_order_id CHECK (order_id > 0),
    CONSTRAINT ck_confirmation_event_type CHECK (event_type = 'OrderConfirmed'),
    CONSTRAINT ck_confirmation_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_confirmation_nano CHECK (confirmed_at_nano BETWEEN 0 AND 999999999)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE product_purchase_activity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at_second BIGINT NOT NULL,
    confirmed_at_nano INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_activity_order_product (order_id, product_id),
    CONSTRAINT fk_purchase_activity_confirmation FOREIGN KEY (order_id)
        REFERENCES processed_order_confirmation (order_id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_order_id CHECK (order_id > 0),
    CONSTRAINT ck_purchase_product_id CHECK (product_id > 0),
    CONSTRAINT ck_purchase_quantity CHECK (quantity > 0),
    CONSTRAINT ck_purchase_nano CHECK (confirmed_at_nano BETWEEN 0 AND 999999999)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
