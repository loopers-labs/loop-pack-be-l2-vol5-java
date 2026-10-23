-- 카드 번호·원문 요청·HMAC 지문을 저장하지 않는다.
-- 식별자는 Java String.equals와 같이 대소문자·끝 공백을 구분하는 collation을 사용한다.
CREATE TABLE `payment_attempt` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `attempt_key` BINARY(16) NOT NULL,
    `provider_account` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    `provider_order_id` VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    `transaction_key` VARCHAR(255) COLLATE utf8mb4_0900_bin NULL,
    `amount` BIGINT NOT NULL,
    `status` ENUM('NOT_OBSERVED', 'PENDING', 'UNKNOWN', 'SUCCESS', 'FAILED') NOT NULL,
    `send_started_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_payment_attempt_order` (`order_id`),
    UNIQUE KEY `uq_payment_attempt_key` (`attempt_key`),
    UNIQUE KEY `uq_payment_attempt_provider_order` (`provider_account`, `provider_order_id`),
    UNIQUE KEY `uq_payment_attempt_provider_transaction` (`provider_account`, `transaction_key`),
    CONSTRAINT `fk_payment_attempt_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_payment_attempt_nonnegative_amount` CHECK (`amount` >= 0),
    CONSTRAINT `ck_payment_attempt_observation_state` CHECK (
        (`status` = 'NOT_OBSERVED' AND `transaction_key` IS NULL)
        OR (`status` IN ('PENDING', 'SUCCESS')
            AND `send_started_at` IS NOT NULL AND `transaction_key` IS NOT NULL)
        OR (`status` IN ('UNKNOWN', 'FAILED') AND `send_started_at` IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;
