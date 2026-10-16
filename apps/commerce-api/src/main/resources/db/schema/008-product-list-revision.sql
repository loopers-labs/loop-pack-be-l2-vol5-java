-- 상품 목록 캐시용 메타데이터다. 운영 중 초기화하면 캐시 namespace도 함께 변경해야 한다.
CREATE TABLE `product_list_revision` (
    `id` BIGINT NOT NULL,
    `revision` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_product_list_revision_singleton` CHECK (`id` = 1),
    CONSTRAINT `ck_product_list_revision_nonnegative` CHECK (`revision` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;

INSERT INTO `product_list_revision` (`id`, `revision`) VALUES (1, 0);
