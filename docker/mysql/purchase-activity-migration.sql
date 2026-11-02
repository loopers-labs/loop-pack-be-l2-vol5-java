-- 구매 활동 소비를 켜기 전에 기존 DB에 한 번 적용한다. 새 DB는 init.sql을 사용한다.
-- commerce-streamer가 소유하는 테이블이며, 이미 확정한 주문의 구매 활동을 소급해 채우지 않는다.
CREATE TABLE IF NOT EXISTS loopers.processed_order_confirmation (
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_confirmation_event (event_id),
    UNIQUE KEY uk_processed_confirmation_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS loopers.product_purchase_activity (
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_activity_order_product (order_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
