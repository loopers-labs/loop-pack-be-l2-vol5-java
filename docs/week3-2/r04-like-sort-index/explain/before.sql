-- 변경 전 스키마(현재 엔티티와 동일한 brands / products / product_like_counts). 스크래치 스키마 r04_before 를 새로 만든다.
DROP DATABASE IF EXISTS r04_before;
CREATE DATABASE r04_before;
USE r04_before;

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
    deleted BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_products_deleted_brand_created (deleted, brand_id, created_at DESC, id DESC),
    KEY idx_products_deleted_brand_price (deleted, brand_id, price ASC, id DESC)
) ENGINE=InnoDB;

CREATE TABLE product_like_counts (
    product_id BIGINT NOT NULL PRIMARY KEY,
    like_count BIGINT NOT NULL
) ENGINE=InnoDB;
