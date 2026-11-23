-- API 단독 테스트 전용: commerce-batch 랭킹 Entity 매핑(랭킹 설계 9.2~9.6·9.10)과 같은 다섯 테이블. 다섯 개가 모두 없을 때만 실행한다.
CREATE TABLE ranking_snapshot (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    deleted_at      DATETIME(6) NULL,
    snapshot_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    revision        BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL,
    input_fixed_at  DATETIME(6) NOT NULL,
    input_count     BIGINT      NOT NULL,
    product_count   BIGINT      NOT NULL,
    input_checksum  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rs_snapshot UNIQUE (snapshot_id),
    CONSTRAINT uk_rs_period_revision UNIQUE (period_start, revision)
) ENGINE = InnoDB;

CREATE TABLE ranking_snapshot_input (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6) NULL,
    snapshot_id  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     BIGINT      NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rsi_snapshot_order_product UNIQUE (snapshot_id, order_id, product_id),
    INDEX idx_rsi_snapshot_product (snapshot_id, product_id)
) ENGINE = InnoDB;

CREATE TABLE ranking_score_stage (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    deleted_at  DATETIME(6)   NULL,
    snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id  BIGINT        NOT NULL,
    score       DECIMAL(65, 0) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rss_snapshot_product UNIQUE (snapshot_id, product_id)
) ENGINE = InnoDB;

CREATE TABLE ranking_result (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    deleted_at      DATETIME(6)   NULL,
    snapshot_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id      BIGINT        NOT NULL,
    score           DECIMAL(65, 0) NOT NULL,
    calculated_rank BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rr_snapshot_product UNIQUE (snapshot_id, product_id),
    CONSTRAINT uk_rr_snapshot_rank UNIQUE (snapshot_id, calculated_rank)
) ENGINE = InnoDB;

CREATE TABLE ranking_publication (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    deleted_at            DATETIME(6) NULL,
    period_start          DATE        NOT NULL,
    published_snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rp_period UNIQUE (period_start)
) ENGINE = InnoDB;
