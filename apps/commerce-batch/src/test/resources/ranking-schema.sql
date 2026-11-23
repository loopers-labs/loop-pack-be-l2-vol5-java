-- batch 는 엔티티가 없어 ddl-auto 가 아무 테이블도 만들지 않는다 (9주차 D-182).
-- 구매 활동 · 처리 기록은 commerce-streamer 엔티티(ProductPurchaseActivity · ProcessedOrderConfirmation),
-- 랭킹 테이블은 commerce-api 엔티티(WeeklyRanking*)와 같은 모양으로 만든다 (D-183). 엔티티를 바꾸면 함께 고친다
CREATE TABLE IF NOT EXISTS processed_order_confirmation (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6),
    event_id     VARCHAR(255) NOT NULL,
    order_id     BIGINT       NOT NULL,
    confirmed_at DATETIME(6)  NOT NULL,
    CONSTRAINT uk_processed_order_confirmation_event_id UNIQUE (event_id),
    CONSTRAINT uk_processed_order_confirmation_order_id UNIQUE (order_id)
);

CREATE TABLE IF NOT EXISTS product_purchase_activity (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    order_id   BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    CONSTRAINT uk_product_purchase_activity_order_product UNIQUE (order_id, product_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    deleted_at       DATETIME(6),
    snapshot_id      VARCHAR(64) NOT NULL,
    period_start     DATE        NOT NULL,
    revision         INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    frozen_at        DATETIME(6) NOT NULL,
    input_count      INT         NOT NULL,
    input_checksum   VARCHAR(64) NOT NULL,
    published_period DATE AS (IF(status = 'PUBLISHED', period_start, NULL)) VIRTUAL,
    CONSTRAINT uk_weekly_ranking_snapshot_snapshot_id UNIQUE (snapshot_id),
    CONSTRAINT uk_weekly_ranking_snapshot_period_revision UNIQUE (period_start, revision),
    CONSTRAINT uk_weekly_ranking_snapshot_published_period UNIQUE (published_period)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_input (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6),
    snapshot_id  VARCHAR(64) NOT NULL,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_weekly_ranking_input_snapshot_order_product UNIQUE (snapshot_id, order_id, product_id),
    INDEX idx_weekly_ranking_input_snapshot_product (snapshot_id, product_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_score (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    deleted_at  DATETIME(6),
    snapshot_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL,
    score       BIGINT      NOT NULL,
    ranking     INT,
    CONSTRAINT uk_weekly_ranking_score_snapshot_product UNIQUE (snapshot_id, product_id),
    INDEX idx_weekly_ranking_score_snapshot_ranking (snapshot_id, ranking)
);
