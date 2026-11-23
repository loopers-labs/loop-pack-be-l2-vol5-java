CREATE TABLE ranking_snapshot (
    snapshot_id VARCHAR(64) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    frozen_at DATETIME(6) NOT NULL,
    input_count INT NOT NULL,
    product_count INT NOT NULL,
    input_checksum CHAR(64) NOT NULL,
    result_checksum CHAR(64) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT uk_ranking_snapshot_period_revision UNIQUE (period_start, revision)
);

CREATE TABLE ranking_snapshot_input (
    snapshot_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY idx_ranking_snapshot_input_product (snapshot_id, product_id)
);

CREATE TABLE ranking_score_stage (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);

CREATE TABLE ranking_result (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    rank_no INT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    CONSTRAINT uk_ranking_result_rank UNIQUE (snapshot_id, rank_no)
);

CREATE TABLE ranking_publication (
    period_start DATE NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (period_start)
);
