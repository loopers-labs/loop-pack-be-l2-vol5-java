-- 001-brand.sql 적용 후 실행한다. 상품 삭제는 deleted_at 갱신이며 FK를 보존한다.
CREATE TABLE `product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `brand_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `price` BIGINT NOT NULL,
    `stock_quantity` INT NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `deleted_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_product_brand_deleted` (`brand_id`, `deleted_at`),
    KEY `idx_product_latest` (`deleted_at`, `created_at` DESC, `id` DESC),
    KEY `idx_product_price` (`deleted_at`, `price` ASC, `id` DESC),
    CONSTRAINT `fk_product_brand` FOREIGN KEY (`brand_id`) REFERENCES `brand` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_product_positive_price` CHECK (`price` >= 1),
    CONSTRAINT `ck_product_nonnegative_stock` CHECK (`stock_quantity` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;
