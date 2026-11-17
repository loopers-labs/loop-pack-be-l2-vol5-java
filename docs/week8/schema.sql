-- W8 새 테이블 DDL (docs/week8/plan.md D-5). local 프로필은 ddl-auto: none이라 로컬 DB(loopers)에 한 번 실행한다.
-- 출처: commerce-api 테스트(ddl-auto: create)가 loopers_test에 만든 테이블의 SHOW CREATE TABLE (2026-11-12).
-- 문자셋·collation은 적지 않는다 — 실행하는 DB의 기본값을 따른다 (W7 schema.sql과 같은 이유).
-- user_coupon은 commerce-streamer가 쓰고 commerce-api가 읽는다. coupon_campaign.issued_count는 streamer의 조건부 UPDATE로만 바뀐다.
-- 외래 키는 두지 않는다 — 다른 표처럼 식별자로만 잇는다.

CREATE TABLE `coupon_campaign` (
  `issued_count` int NOT NULL COMMENT '지금까지 발급한 당첨자 수. consumer의 조건부 UPDATE로만 바뀐다 (D-5)',
  `queue_capacity` int NOT NULL COMMENT '지금 기다릴 수 있는 최대 인원(ZCARD 기준), 당첨자 수 이상 (CPN-02, D-4-5)',
  `total_quantity` int NOT NULL COMMENT '당첨자 수 — 쿠폰팩을 받는 사람 수, 1 이상 (CPN-01)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issue_end_at` datetime(6) NOT NULL COMMENT '발급 마감 시각 (제외)',
  `issue_start_at` datetime(6) NOT NULL COMMENT '발급 시작 시각 (포함)',
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(100) NOT NULL COMMENT '캠페인 이름, 1–100자 (CPN-01)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE `coupon` (
  `stackable` bit(1) NOT NULL COMMENT '다른 쿠폰과 함께 쓸 수 있는가. W1 계약 A-1(한 주문에 한 장)과 부딪쳐 사용 기능을 만들 때 다시 정한다',
  `valid_days` int DEFAULT NULL COMMENT 'DAYS_FROM_ISSUE 발급 후 사용 가능 일수, 1 이상',
  `campaign_id` bigint NOT NULL COMMENT '이 쿠폰을 담은 캠페인',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `discount_value` bigint NOT NULL COMMENT 'FIXED: 할인액(원, 1 이상) · RATE: 할인율(%, 1–100)',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `max_discount_amount` bigint DEFAULT NULL COMMENT 'RATE의 최대 할인액(원). FIXED는 없음',
  `min_order_amount` bigint NOT NULL COMMENT '쓸 수 있는 최소 주문 금액(원), 0 이상',
  `updated_at` datetime(6) NOT NULL,
  `valid_from` datetime(6) DEFAULT NULL COMMENT 'FIXED_PERIOD 사용 시작 시각',
  `valid_until` datetime(6) DEFAULT NULL COMMENT 'FIXED_PERIOD 사용 끝 시각',
  `name` varchar(100) NOT NULL COMMENT '쿠폰 이름, 1–100자 (CPN-04)',
  `discount_type` enum('FIXED','RATE') NOT NULL COMMENT 'FIXED 정액 | RATE 정률 (CPN-04)',
  `scope` enum('ALL','PRODUCTS') NOT NULL COMMENT 'ALL 모든 상품 | PRODUCTS 적용 상품만 (CPN-05)',
  `validity_type` enum('DAYS_FROM_ISSUE','FIXED_PERIOD') NOT NULL COMMENT 'FIXED_PERIOD 정해진 기간 | DAYS_FROM_ISSUE 발급 후 N일 (CPN-06)',
  PRIMARY KEY (`id`),
  KEY `idx_coupon_campaign` (`campaign_id`)
) ENGINE=InnoDB;

CREATE TABLE `coupon_applicable_product` (
  `coupon_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  PRIMARY KEY (`coupon_id`,`product_id`)
) ENGINE=InnoDB;

CREATE TABLE `user_coupon` (
  `campaign_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issued_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `used_order_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `status` enum('AVAILABLE','EXPIRED','USED') NOT NULL COMMENT 'AVAILABLE | USED | EXPIRED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_user` (`coupon_id`,`user_id`),
  KEY `idx_user_coupon_user` (`user_id`),
  KEY `idx_user_coupon_campaign_user` (`campaign_id`,`user_id`)
) ENGINE=InnoDB;

CREATE TABLE `coupon_issue_outbox` (
  `attempt_count` int NOT NULL,
  `campaign_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `next_attempt_at` datetime(6) NOT NULL COMMENT 'PENDING: 다음 시도 가능 시각 · SENDING: 맡은 기한(lease)',
  `published_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `message_key` varchar(50) NOT NULL COMMENT 'partition을 고르는 key — userId 문자열. 사용자 사이 순서는 필요 없어 partition에 고르게 퍼진다',
  `last_error` varchar(1000) DEFAULT NULL,
  `payload` text NOT NULL COMMENT '보낼 JSON 원문 {campaignId, userId}',
  `status` enum('BLOCKED','PENDING','PUBLISHED','SENDING') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_issue_outbox_campaign_user` (`campaign_id`,`user_id`),
  KEY `idx_coupon_issue_outbox_due` (`status`,`next_attempt_at`,`id`)
) ENGINE=InnoDB;
