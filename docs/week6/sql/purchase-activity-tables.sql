-- 구매 활동을 저장하는 MySQL DB에 적용한다. 현재 상품 테이블을 참조하는 FK는 두지 않는다.
CREATE TABLE processed_order_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(255) NOT NULL,
    order_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    items_signature TEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_confirmation_event UNIQUE (event_id),
    CONSTRAINT uk_confirmation_order UNIQUE (order_id)
);

CREATE TABLE product_purchase_activity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_activity_order_product UNIQUE (order_id, product_id)
);
