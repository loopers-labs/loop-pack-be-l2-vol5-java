-- 변경 후 스키마(products.like_count + 좋아요순 인덱스 2개). r04_before 데이터를 복사해 r04_after 를 만든다. (seed.sql 실행 후)
DROP DATABASE IF EXISTS r04_after;
CREATE DATABASE r04_after;
USE r04_after;

CREATE TABLE brands (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    deleted BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_brands_deleted_created (deleted, created_at DESC, id DESC)
) ENGINE=InnoDB;

CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    price BIGINT NOT NULL,
    stock INT NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    deleted BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_products_deleted_brand_created (deleted, brand_id, created_at DESC, id DESC),
    KEY idx_products_deleted_brand_price (deleted, brand_id, price ASC, id DESC),
    KEY idx_products_deleted_like (deleted, like_count DESC, id DESC),
    KEY idx_products_deleted_brand_like (deleted, brand_id, like_count DESC, id DESC)
) ENGINE=InnoDB;

INSERT INTO brands SELECT * FROM r04_before.brands;

INSERT INTO products (id, brand_id, name, description, price, stock, like_count, deleted, created_at, updated_at)
SELECT p.id, p.brand_id, p.name, p.description, p.price, p.stock, COALESCE(c.like_count, 0),
       p.deleted, p.created_at, p.updated_at
FROM r04_before.products p
LEFT JOIN r04_before.product_like_counts c ON c.product_id = p.id;

ANALYZE TABLE brands, products;
