-- W9 새 테이블 DDL (docs/week9/plan.md D-2·D-3·D-4). local 프로필은 ddl-auto: none이라 로컬 DB(loopers)에 한 번 실행한다.
-- 원본 product_purchase_activity는 W6 docs/week6/schema.sql에 있다.
-- 출처: 테스트(ddl-auto: create)가 loopers_test에 만든 테이블의 SHOW CREATE TABLE (2026-11-18). 문자셋·정렬은 로컬 DB(loopers)와 W6 schema.sql에 맞춰 utf8mb4_general_ci로 적었다.

CREATE TABLE `ranking_snapshot` (
  `period_start` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `frozen_at` datetime(6) NOT NULL,
  `input_count` bigint NOT NULL,
  `input_quantity_sum` bigint NOT NULL,
  `revision` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `snapshot_id` varchar(32) NOT NULL,
  `input_checksum` varchar(64) NOT NULL,
  `status` enum('COMPLETED','FROZEN') NOT NULL,
  PRIMARY KEY (`snapshot_id`),
  UNIQUE KEY `uk_ranking_snapshot_period_revision` (`period_start`,`revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `ranking_snapshot_input` (
  `quantity` int NOT NULL,
  `confirmed_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `snapshot_id` varchar(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_input_product_order` (`snapshot_id`,`product_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `weekly_ranking` (
  `rank_no` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `score` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `snapshot_id` varchar(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_weekly_ranking_snapshot_product` (`snapshot_id`,`product_id`),
  KEY `idx_weekly_ranking_snapshot_rank` (`snapshot_id`,`rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `ranking_publication` (
  `period_start` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `published_at` datetime(6) NOT NULL,
  `revision` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `snapshot_id` varchar(32) NOT NULL,
  PRIMARY KEY (`period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

