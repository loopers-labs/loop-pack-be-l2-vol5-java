-- Additive score stage. Existing frozen inputs and Batch metadata must remain intact.
-- COMPLETE is reserved for the later publication transaction; calculation alone does not publish a ranking.
ALTER TABLE weekly_ranking_snapshot
    ADD COLUMN calculation_state VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'CALCULATING',
    ADD CONSTRAINT ck_ranking_calculation_state CHECK (calculation_state IN ('CALCULATING', 'COMPLETE'));

CREATE TABLE weekly_ranking_score (
    snapshot_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    CONSTRAINT fk_weekly_score_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshot (snapshot_id) ON DELETE RESTRICT,
    CONSTRAINT ck_weekly_score_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_score_value CHECK (score > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
