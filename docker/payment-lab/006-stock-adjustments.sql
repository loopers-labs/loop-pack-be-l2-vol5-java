USE loopers;
CREATE TABLE IF NOT EXISTS stock_adjustments (
    order_id BIGINT NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS stock_adjustment_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT uk_stock_adjustment_product UNIQUE (order_id, product_id),
    CONSTRAINT fk_stock_item_adjustment FOREIGN KEY (order_id) REFERENCES stock_adjustments (order_id)
);
