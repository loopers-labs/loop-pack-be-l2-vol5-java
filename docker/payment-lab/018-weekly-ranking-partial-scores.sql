-- Per-order-partition contributions are immutable once inserted. Only the finalizer writes the global stage.
USE loopers;

CREATE TABLE weekly_ranking_partial_scores (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_partition_id INT NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, order_partition_id, product_id),
    CONSTRAINT fk_weekly_partial_order_partition FOREIGN KEY (snapshot_id, order_partition_id)
        REFERENCES weekly_ranking_order_partitions(snapshot_id, order_partition_id),
    CHECK (product_id > 0 AND score > 0)
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_score_conflicts (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_partition_id INT NOT NULL,
    product_id BIGINT NOT NULL,
    stored_score BIGINT NOT NULL,
    attempted_score BIGINT NOT NULL,
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_partition_id, product_id),
    CONSTRAINT fk_weekly_score_conflict_order_partition FOREIGN KEY (snapshot_id, order_partition_id)
        REFERENCES weekly_ranking_order_partitions(snapshot_id, order_partition_id),
    CHECK (stored_score <> attempted_score AND stored_score > 0 AND attempted_score > 0)
) ENGINE=InnoDB;
