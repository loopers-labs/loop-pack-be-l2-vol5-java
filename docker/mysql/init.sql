-- 조회 성능 실습용 DB. 데이터 적재는 seedProductQuery에서 실행한다.
CREATE DATABASE IF NOT EXISTS loopers
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON loopers.* TO 'application'@'%';
USE loopers;

CREATE TABLE IF NOT EXISTS brands (
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    updated_at DATETIME(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_brand_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS users (
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS products (
    brand_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    price BIGINT NOT NULL,
    stock BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    KEY FKa3a4mpsfdf4d2y6r8ra3sc8mv (brand_id),
    KEY idx_products_brand_deleted_price (brand_id, deleted_at, price),
    CONSTRAINT FKa3a4mpsfdf4d2y6r8ra3sc8mv FOREIGN KEY (brand_id) REFERENCES brands (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS likes (
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_like_user_product (user_id, product_id),
    KEY idx_likes_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS orders (
    confirmed_at DATETIME(6) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_amount BIGINT DEFAULT NULL,
    total_amount BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT DEFAULT NULL,
    payment_method ENUM('PG', 'POINT') DEFAULT NULL,
    payment_result ENUM('FAILED', 'SUCCESS') DEFAULT NULL,
    status ENUM('CONFIRMED', 'DRAFT', 'PAYMENT_FAILED', 'PAYMENT_PENDING') DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS order_items (
    quantity INT NOT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    unit_price BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    KEY FKbioxgbv59vetrxe0ejfubep1w (order_id),
    KEY idx_order_items_product_id (product_id),
    CONSTRAINT FKbioxgbv59vetrxe0ejfubep1w FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS payment_attempts (
    amount BIGINT NOT NULL,
    conflict_detected_at DATETIME(6) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    last_checked_at DATETIME(6) DEFAULT NULL,
    order_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    payment_attempt_key VARCHAR(36) NOT NULL,
    provider_order_id VARCHAR(64) NOT NULL,
    transaction_key VARCHAR(255) DEFAULT NULL,
    conflict_reason ENUM('RESULT_CONTRADICTION', 'TRANSACTION_MISMATCH', 'MULTIPLE_TRANSACTIONS') DEFAULT NULL,
    status ENUM('FAILED', 'PENDING', 'REQUESTED', 'SUCCEEDED', 'UNKNOWN') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_attempts_key (payment_attempt_key),
    UNIQUE KEY uk_payment_attempts_order_id (order_id),
    UNIQUE KEY uk_payment_attempts_provider_order_id (provider_order_id),
    KEY idx_payment_attempts_status_checked (status, last_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS points (
    balance BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UKswg8y3uo5dm5psbnesgeu1my (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS example (
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    updated_at DATETIME(6) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    name VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
