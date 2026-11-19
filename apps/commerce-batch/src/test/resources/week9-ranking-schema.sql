CREATE TABLE IF NOT EXISTS product_purchase_activity (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot (
    snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
    period_start DATE NOT NULL UNIQUE,
    period_end DATE NOT NULL,
    input_fixed_at DATETIME(6) NOT NULL,
    input_count BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot_input (
    snapshot_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_score (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_item (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    rank_no BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY uk_weekly_ranking_item_rank_test (snapshot_id, rank_no)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_publication (
    period_start DATE NOT NULL PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE
);
