CREATE TABLE weekly_ranking_snapshot (
    snapshot_id VARCHAR(64) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    input_fixed_at DATETIME(6) NOT NULL,
    input_count BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_weekly_ranking_snapshot_period (period_start)
);

CREATE TABLE weekly_ranking_snapshot_input (
    snapshot_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY idx_weekly_ranking_snapshot_input_product (snapshot_id, product_id)
);

CREATE TABLE weekly_ranking_score (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);

CREATE TABLE weekly_ranking_item (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    rank_no BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY uk_weekly_ranking_item_rank (snapshot_id, rank_no)
);

CREATE TABLE weekly_ranking_publication (
    period_start DATE NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (period_start),
    UNIQUE KEY uk_weekly_ranking_publication_snapshot (snapshot_id)
);
