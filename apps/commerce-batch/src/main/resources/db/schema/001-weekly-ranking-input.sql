-- Additive W9 schema for the existing purchase-activity DB. Apply only through an explicit migration.
-- No source, Batch metadata, or previously committed snapshot is recreated or deleted here.
CREATE TABLE weekly_ranking_snapshot (
    snapshot_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision INT NOT NULL,
    frozen_at_second BIGINT NOT NULL,
    frozen_at_nano INT NOT NULL,
    input_count BIGINT NOT NULL,
    product_count BIGINT NOT NULL,
    checksum_version VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    input_checksum VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    input_state VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_weekly_ranking_period_revision (period_start, revision),
    CONSTRAINT ck_ranking_revision CHECK (revision > 0),
    CONSTRAINT ck_ranking_week CHECK (WEEKDAY(period_start) = 0 AND DATEDIFF(period_end, period_start) = 7),
    CONSTRAINT ck_ranking_frozen_nano CHECK (frozen_at_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_ranking_input_count CHECK (input_count >= 0),
    CONSTRAINT ck_ranking_product_count CHECK (product_count >= 0 AND product_count <= input_count),
    CONSTRAINT ck_ranking_checksum_version CHECK (checksum_version = 'v1'),
    CONSTRAINT ck_ranking_checksum_length CHECK (CHAR_LENGTH(input_checksum) = 64),
    CONSTRAINT ck_ranking_input_state CHECK (input_state = 'FROZEN')
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE weekly_ranking_input (
    snapshot_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at_second BIGINT NOT NULL,
    confirmed_at_nano INT NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY idx_weekly_input_product (snapshot_id, product_id, order_id),
    CONSTRAINT fk_weekly_input_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshot (snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT ck_weekly_input_order CHECK (order_id > 0),
    CONSTRAINT ck_weekly_input_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_input_quantity CHECK (quantity > 0),
    CONSTRAINT ck_weekly_input_nano CHECK (confirmed_at_nano BETWEEN 0 AND 999999999)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
