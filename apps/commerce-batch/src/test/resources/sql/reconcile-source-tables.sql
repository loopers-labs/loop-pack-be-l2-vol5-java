-- 대사가 읽는 원본 테이블. 실제로는 commerce-api(orders)와 commerce-streamer(processed_order_confirmation)의 엔티티가
-- 만드는 테이블이며, batch 테스트 DB에는 엔티티가 없어 대사가 쓰는 열·인덱스를 같은 모양으로 만든다.
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    total BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    confirmed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_orders_status_confirmed_at (status, confirmed_at)
);

CREATE TABLE IF NOT EXISTS processed_order_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(255) NOT NULL,
    order_id BIGINT NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_processed_order_confirmation_event_id UNIQUE (event_id),
    CONSTRAINT uk_processed_order_confirmation_order_id UNIQUE (order_id)
);
