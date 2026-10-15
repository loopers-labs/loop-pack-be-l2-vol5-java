-- ============================================================
-- 성능 측정용 DB 스키마 (엔티티 기반 직접 생성)
-- mysql -h 127.0.0.1 -P 3307 -uapplication -papplication loopers < schema.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS brands (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    deleted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS users (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    deleted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS products (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    brand_id   BIGINT        NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    price      BIGINT        NOT NULL,
    stock      INT           NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    deleted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS likes (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    CONSTRAINT uk_likes_user_product UNIQUE (user_id, product_id)
);

CREATE TABLE IF NOT EXISTS points (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE,
    balance    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    total_amount BIGINT       NOT NULL,
    paid_amount  BIGINT,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)
);

CREATE TABLE IF NOT EXISTS order_items (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    unit_price BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6)
);
