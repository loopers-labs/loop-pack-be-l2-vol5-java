-- Apply once after 015. Preserve frozen inputs, stage and all existing Batch metadata.
USE loopers;

ALTER TABLE weekly_ranking_snapshots
    ADD CONSTRAINT uk_weekly_snapshot_publication_contract UNIQUE (period_start, revision, snapshot_id);

CREATE TABLE weekly_ranking_completions (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_contract_version INT NOT NULL,
    product_count BIGINT NOT NULL,
    result_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_weekly_completion_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshots (snapshot_id),
    CONSTRAINT ck_weekly_completion_contract CHECK (result_contract_version > 0),
    CONSTRAINT ck_weekly_completion_count CHECK (product_count >= 0),
    CONSTRAINT ck_weekly_completion_checksum CHECK (result_checksum REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_rankings (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    ranking_position BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    CONSTRAINT uk_weekly_ranking_position UNIQUE (snapshot_id, ranking_position),
    CONSTRAINT fk_weekly_ranking_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_snapshots (snapshot_id),
    CONSTRAINT ck_weekly_ranking_product CHECK (product_id > 0),
    CONSTRAINT ck_weekly_ranking_score CHECK (score > 0),
    CONSTRAINT ck_weekly_ranking_position CHECK (ranking_position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_ranking_publications (
    period_start DATE NOT NULL,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision INT NULL,
    PRIMARY KEY (period_start),
    CONSTRAINT fk_weekly_publication_completion FOREIGN KEY (snapshot_id)
        REFERENCES weekly_ranking_completions (snapshot_id),
    CONSTRAINT fk_weekly_publication_contract FOREIGN KEY (period_start, revision, snapshot_id)
        REFERENCES weekly_ranking_snapshots (period_start, revision, snapshot_id),
    CONSTRAINT ck_weekly_publication_period CHECK (DAYOFWEEK(period_start) = 2),
    CONSTRAINT ck_weekly_publication_pointer CHECK (
        (snapshot_id IS NULL AND revision IS NULL)
        OR (snapshot_id IS NOT NULL AND revision IS NOT NULL AND revision > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
