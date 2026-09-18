-- 상품별 합산 결과 한 행과 생성 당시 상품명·단가를 보존한다.
CREATE TABLE `order_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `product_name_snapshot` VARCHAR(100) NOT NULL,
    `unit_price_snapshot` BIGINT NOT NULL,
    `quantity` INT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_order_item_order_product` (`order_id`, `product_id`),
    KEY `idx_order_item_product` (`product_id`),
    CONSTRAINT `fk_order_item_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_item_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_order_item_nonnegative_price` CHECK (`unit_price_snapshot` >= 0),
    CONSTRAINT `ck_order_item_positive_quantity` CHECK (`quantity` > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;
