-- Additive publication schema. Never rewrite frozen inputs, scores or persistent Batch metadata.
ALTER TABLE weekly_ranking_snapshot
    ADD COLUMN result_count BIGINT NULL,
    ADD COLUMN result_checksum_version VARCHAR(16) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN result_checksum VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    ADD UNIQUE KEY uk_ranking_period_identity (period_start, snapshot_id, revision),
    ADD CONSTRAINT ck_ranking_result_manifest CHECK (
        (calculation_state = 'CALCULATING' AND result_count IS NULL
            AND result_checksum_version IS NULL AND result_checksum IS NULL)
        OR (calculation_state = 'COMPLETE' AND result_count IS NOT NULL
            AND result_count = product_count AND result_checksum_version IS NOT NULL
            AND result_checksum_version = 'v1' AND result_checksum IS NOT NULL
            AND CHAR_LENGTH(result_checksum) = 64)
    );

CREATE TABLE weekly_ranking_result (
    snapshot_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    ranking_position BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY uk_weekly_ranking_position (snapshot_id, ranking_position),
    CONSTRAINT fk_weekly_result_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshot (snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT ck_weekly_result_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_result_score CHECK (score > 0),
    CONSTRAINT ck_weekly_result_position CHECK (ranking_position > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE weekly_ranking_publication (
    period_start DATE NOT NULL,
    snapshot_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    revision INT NOT NULL,
    PRIMARY KEY (period_start),
    CONSTRAINT fk_weekly_publication_identity FOREIGN KEY (period_start, snapshot_id, revision)
        REFERENCES weekly_ranking_snapshot (period_start, snapshot_id, revision) ON DELETE RESTRICT,
    CONSTRAINT ck_weekly_publication_revision CHECK (revision > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
