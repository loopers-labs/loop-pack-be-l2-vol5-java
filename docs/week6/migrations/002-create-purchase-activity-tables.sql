CREATE TABLE processed_order_confirmation (
    event_id VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_processed_confirmation_order_id (order_id)
);

CREATE TABLE product_purchase_activity (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
