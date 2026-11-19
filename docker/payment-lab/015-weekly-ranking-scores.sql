-- Apply once after 014. Preserve frozen inputs and all existing Batch metadata.
USE loopers;

CREATE TABLE weekly_ranking_score_stage (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    CONSTRAINT fk_weekly_score_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshots (snapshot_id),
    CONSTRAINT ck_weekly_score_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_score_positive CHECK (score > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE weekly_ranking_snapshot_inputs
    ADD INDEX ix_weekly_snapshot_input_product_quantity (snapshot_id, product_id, quantity);
