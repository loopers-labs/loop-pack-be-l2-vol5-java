-- 실험 DB(loopers_w6)에 W5·W6 스키마 외에 필요한 테이블 — 스타터의 example (ddl-auto=validate가 확인한다).
CREATE TABLE IF NOT EXISTS `example` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
