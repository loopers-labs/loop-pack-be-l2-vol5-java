CREATE TABLE `coupon_campaign` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `active` BIT(1) NOT NULL,
    `total_quota` INT NOT NULL,
    `waiting_count` INT NOT NULL DEFAULT 0,
    `issued_count` INT NOT NULL DEFAULT 0,
    `benefit_description` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_coupon_campaign_quota` CHECK (
        `total_quota` >= 0 AND `waiting_count` >= 0 AND `issued_count` >= 0
        AND `waiting_count` + `issued_count` <= `total_quota`
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE `coupon_reservation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `campaign_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `status` ENUM('WAITING', 'ISSUED') NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_coupon_reservation_campaign_user` (`campaign_id`, `user_id`),
    KEY `idx_coupon_reservation_user` (`user_id`),
    CONSTRAINT `fk_coupon_reservation_campaign` FOREIGN KEY (`campaign_id`) REFERENCES `coupon_campaign` (`id`),
    CONSTRAINT `fk_coupon_reservation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE `owned_coupon` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `reservation_id` BIGINT NOT NULL,
    `benefit_description` VARCHAR(255) NOT NULL,
    `issued_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_owned_coupon_reservation` (`reservation_id`),
    CONSTRAINT `fk_owned_coupon_reservation` FOREIGN KEY (`reservation_id`) REFERENCES `coupon_reservation` (`id`)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;
