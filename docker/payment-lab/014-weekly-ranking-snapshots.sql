-- Prepare once after commerce 013. Preserve source activities and existing Batch metadata.
USE loopers;

CREATE TABLE weekly_ranking_snapshots (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision INT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_contract_version INT NOT NULL,
    frozen_at DATETIME(6) NULL,
    input_count BIGINT NULL,
    input_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT uk_weekly_snapshot_period_revision UNIQUE (period_start, revision),
    CONSTRAINT ck_weekly_snapshot_period CHECK (
        DAYOFWEEK(period_start) = 2 AND period_end = DATE_ADD(period_start, INTERVAL 7 DAY)
    ),
    CONSTRAINT ck_weekly_snapshot_revision CHECK (revision > 0),
    CONSTRAINT ck_weekly_snapshot_contract CHECK (input_contract_version > 0),
    CONSTRAINT ck_weekly_snapshot_state CHECK (
        (state = 'FREEZING' AND frozen_at IS NULL AND input_count IS NULL AND input_checksum IS NULL)
        OR
        (state = 'FROZEN' AND frozen_at IS NOT NULL AND input_count IS NOT NULL AND input_count >= 0
            AND input_checksum IS NOT NULL AND CHAR_LENGTH(input_checksum) = 64)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_ranking_snapshot_inputs (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    CONSTRAINT fk_weekly_snapshot_input_manifest FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshots (snapshot_id),
    CONSTRAINT ck_weekly_snapshot_input_order CHECK (order_id > 0),
    CONSTRAINT ck_weekly_snapshot_input_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_snapshot_input_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
