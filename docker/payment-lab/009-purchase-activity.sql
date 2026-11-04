-- 신규 Inbox와 구매 활동 테이블을 생성한다.
USE loopers;

CREATE TABLE order_confirmation_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    normalized_payload LONGTEXT NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_inbox_event_id UNIQUE (event_id),
    CONSTRAINT uk_purchase_inbox_order_id UNIQUE (order_id),
    CONSTRAINT ck_purchase_inbox_order_positive CHECK (order_id > 0),
    CONSTRAINT ck_purchase_inbox_contract CHECK (event_type = 'OrderConfirmed' AND schema_version = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_purchase_activities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inbox_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_activities_order_product UNIQUE (order_id, product_id),
    CONSTRAINT fk_purchase_activities_inbox FOREIGN KEY (inbox_id) REFERENCES order_confirmation_inbox (id),
    CONSTRAINT ck_purchase_activity_order_positive CHECK (order_id > 0),
    CONSTRAINT ck_purchase_activity_product_positive CHECK (product_id > 0),
    CONSTRAINT ck_purchase_activity_quantity_positive CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
