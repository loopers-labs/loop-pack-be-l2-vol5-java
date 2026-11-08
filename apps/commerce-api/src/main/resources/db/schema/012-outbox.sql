-- Additive W7 migration. Run against the same datasource as the order confirmation transaction.
-- JSON is stored as completed text; delivery must not reconstruct facts from current product data.
-- NO PAD binary collation preserves Java identity comparisons, including case and trailing spaces.
CREATE TABLE `outbox_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    `aggregate_type` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    `aggregate_id` VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    `event_type` VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    `schema_version` INT NOT NULL,
    `topic` VARCHAR(249) COLLATE utf8mb4_0900_bin NOT NULL,
    `message_key` VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    `payload` LONGTEXT COLLATE utf8mb4_0900_bin NOT NULL,
    `status` ENUM('PENDING', 'PUBLISHED') NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `published_at` DATETIME(6) NULL,
    `attempt_count` INT NOT NULL,
    `next_attempt_at` DATETIME(6) NOT NULL,
    `last_error` VARCHAR(1000) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_outbox_event_id` (`event_id`),
    KEY `idx_outbox_due` (`status`, `next_attempt_at`, `id`),
    CONSTRAINT `ck_outbox_schema_version` CHECK (`schema_version` > 0),
    CONSTRAINT `ck_outbox_attempt_count` CHECK (`attempt_count` >= 0),
    CONSTRAINT `ck_outbox_payload_json` CHECK (JSON_VALID(`payload`)),
    CONSTRAINT `ck_outbox_publication_time` CHECK (
        (`status` = 'PENDING' AND `published_at` IS NULL)
        OR (`status` = 'PUBLISHED' AND `published_at` IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_bin;
