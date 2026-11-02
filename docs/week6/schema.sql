-- W6 새 테이블 DDL (docs/week6/plan.md ADR-W6-03·05). local 프로필은 ddl-auto: none이라 로컬 DB(loopers)에 한 번 실행한다.
-- 출처: 테스트(ddl-auto: create)가 loopers_test에 만든 테이블의 SHOW CREATE TABLE (2026-10-29).

CREATE TABLE `event_outbox` (
  `published_partition` int DEFAULT NULL,
  `aggregate_id` bigint NOT NULL COMMENT '대상 ID — 주문 ID',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `published_at` datetime(6) DEFAULT NULL COMMENT 'send 콜백이 성공을 알린 시각. null이면 미발행 — 누락 판정 기준',
  `published_offset` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `event_id` varchar(36) COLLATE utf8mb4_general_ci NOT NULL COMMENT '이벤트 신원(UUID). 재발행해도 같은 값 (ADR-W6-01)',
  `aggregate_type` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '이벤트를 낸 대상 종류 — ORDER',
  `event_type` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '이벤트 종류 — OrderConfirmed',
  `message_key` varchar(200) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'partition을 고르는 key — 주문 ID 문자열',
  `topic` varchar(200) COLLATE utf8mb4_general_ci NOT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '마지막 send 실패 원인 — 진단용. 판정은 published_at으로만',
  `payload` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '보낸 JSON 원문. MySQL JSON 타입은 키 순서·공백을 다시 만들어 원문이 보존되지 않아 TEXT',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_outbox_event_id` (`event_id`),
  KEY `idx_event_outbox_unpublished` (`published_at`,`created_at`),
  KEY `idx_event_outbox_aggregate` (`aggregate_type`,`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `consumed_order_confirmation` (
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '같은 주문에 다른 이벤트를 받지 않는다',
  `updated_at` datetime(6) NOT NULL,
  `event_id` varchar(36) COLLATE utf8mb4_general_ci NOT NULL COMMENT '같은 이벤트를 두 번 처리하지 않는다',
  `fingerprint` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '정규화한 사실(주문·확정 순간·상품별 수량)의 SHA-256',
  `payload` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '받은 원문 — 충돌 조사용',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consumed_confirmation_event` (`event_id`),
  UNIQUE KEY `uk_consumed_confirmation_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `product_purchase_activity` (
  `quantity` int NOT NULL,
  `confirmed_at` datetime(6) NOT NULL COMMENT '주문의 최초 확정 시각 (이벤트 값)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL COMMENT '과거 주문의 상품 ID — 현재 상품 행의 존재·가격과 무관하게 보존',
  `updated_at` datetime(6) NOT NULL,
  `event_id` varchar(36) COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_activity_order_product` (`order_id`,`product_id`),
  KEY `idx_purchase_activity_product_confirmed` (`product_id`,`confirmed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
