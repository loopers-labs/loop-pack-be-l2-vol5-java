-- 현재 도메인의 두 상태와 생성 금액·확정 결제액을 저장한다.
CREATE TABLE `order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `status` ENUM('DRAFT', 'CONFIRMED') NOT NULL,
    `total_amount` BIGINT NOT NULL,
    `paid_amount` BIGINT NULL,
    `confirmed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_order_user_created` (`user_id`, `created_at` DESC, `id` DESC),
    KEY `idx_order_created` (`created_at` DESC, `id` DESC),
    CONSTRAINT `fk_order_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_order_nonnegative_total` CHECK (`total_amount` >= 0),
    CONSTRAINT `ck_order_nonnegative_payment` CHECK (`paid_amount` IS NULL OR `paid_amount` >= 0),
    CONSTRAINT `ck_order_payment_state` CHECK (
        (`status` = 'DRAFT' AND `paid_amount` IS NULL AND `confirmed_at` IS NULL)
        OR (`status` = 'CONFIRMED' AND `paid_amount` IS NOT NULL
            AND `paid_amount` = `total_amount` AND `confirmed_at` IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;
