-- 테스트 전용: streamer ProductPurchaseActivityModel 매핑 + idx_ppa_confirmed_at. 없을 때만 실행한다.
CREATE TABLE product_purchase_activity (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6) NULL,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     BIGINT      NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ppa_order_product UNIQUE (order_id, product_id),
    INDEX idx_ppa_confirmed_at (confirmed_at),
    CONSTRAINT ck_ppa_order_id CHECK (order_id > 0),
    CONSTRAINT ck_ppa_product_id CHECK (product_id > 0),
    CONSTRAINT ck_ppa_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;
