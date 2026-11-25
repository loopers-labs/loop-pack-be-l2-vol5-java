-- 테스트 전용: commerce-batch 는 W6 의 구매 활동 엔티티(commerce-streamer ProductPurchaseActivityModel, 설계 TB-13)를 갖지 않는다.
-- 그 엔티티가 만드는 테이블과 같은 모양을 만든다. 실제 엔티티 스키마와의 일치는 tests/commerce-e2e 의 WeeklyRankingFlowTest 가 확인한다.
CREATE TABLE IF NOT EXISTS product_purchase_activity (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_product_purchase_activity_order_product (order_id, product_id),
    KEY ix_product_purchase_activity_confirmed_at (confirmed_at)
) ENGINE = InnoDB;
