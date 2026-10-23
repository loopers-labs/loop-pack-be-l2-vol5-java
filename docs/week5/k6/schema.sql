-- 5주차 k6 실험 DB(loopers_k6) 스키마. 현재 엔티티로 테스트가 만든 loopers_test 에서 뽑았다 (mysqldump --no-data).
-- 다시 만들 때: docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_k6 < docs/week5/k6/schema.sql

CREATE TABLE `brands` (
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '브랜드 이름, 1–50자 (BRD-01)',
  `description` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '브랜드 설명, 0–200자 (BRD-01)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `products` (
  `stock` int NOT NULL COMMENT '재고 수량, 0 이상 (PRD-01)',
  `brand_id` bigint NOT NULL COMMENT '브랜드 식별자 (ADR-01)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `price` bigint NOT NULL COMMENT '판매 단가, 1–1억 원 (PRD-01)',
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '상품 이름, 1–100자 (PRD-01)',
  PRIMARY KEY (`id`),
  KEY `idx_products_brand` (`brand_id`),
  KEY `idx_products_brand_deleted_price_id` (`brand_id`,`deleted_at`,`price`,`id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `users` (
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '사용자 이름',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `likes` (
  `created_at` datetime(6) NOT NULL COMMENT '좋아요 등록 시각. 멱등 INSERT가 채운다 (ADR-13)',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL COMMENT '좋아요 대상 상품 식별자. user_id와 쌍으로 unique (LIK-01)',
  `user_id` bigint NOT NULL COMMENT '좋아요한 사용자 식별자 (LIK-01)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_likes_user_product` (`user_id`,`product_id`),
  KEY `idx_likes_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `orders` (
  `confirmed_at` datetime(6) DEFAULT NULL COMMENT '확정 시각. DRAFT는 없음 (ORD-02)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paid_amount` bigint DEFAULT NULL COMMENT '결제 금액. 확정 시 합계로 고정, DRAFT는 없음 (ORD-05)',
  `total_amount` bigint NOT NULL COMMENT '품목 금액 합계 (ORD-01)',
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL COMMENT '주문자 식별자 (ADR-01)',
  `payment_method` enum('PG','POINT') COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '결제 수단 — POINT(W2~W4 확정) 또는 PG(W5 결제 시작), DRAFT는 없음 (ORD-05)',
  `status` enum('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING') COLLATE utf8mb4_general_ci NOT NULL COMMENT '주문 상태 — DRAFT·PAYMENT_PENDING·CONFIRMED·PAYMENT_FAILED (ORD-02, W5 3-1)',
  PRIMARY KEY (`id`),
  KEY `idx_orders_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `order_items` (
  `quantity` int NOT NULL COMMENT '주문 수량, 같은 상품은 합산 (P-01)',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL COMMENT '상품 식별자 (ADR-01)',
  `unit_price` bigint NOT NULL COMMENT '주문 당시 단가 snapshot (ORD-01)',
  `product_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '주문 당시 상품명 snapshot (ORD-01)',
  PRIMARY KEY (`id`),
  KEY `FKbioxgbv59vetrxe0ejfubep1w` (`order_id`),
  CONSTRAINT `FKbioxgbv59vetrxe0ejfubep1w` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `point_groups` (
  `amount` bigint NOT NULL COMMENT '충전 금액 (PNT-02)',
  `charged_at` datetime(6) NOT NULL COMMENT '충전 시각 (PNT-02)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL COMMENT '만료 시각 = 충전 시각 + 유효기간. 정각부터 사용 불가 (P-13·P-20)',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `remaining` bigint NOT NULL COMMENT '남은 금액 (PNT-06)',
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL COMMENT '소유 사용자 식별자',
  `type` enum('CHARGE') COLLATE utf8mb4_general_ci NOT NULL COMMENT '그룹을 만든 경로 — CHARGE (P-13)',
  PRIMARY KEY (`id`),
  KEY `idx_point_groups_user_expires` (`user_id`,`expires_at`),
  KEY `idx_point_groups_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `point_histories` (
  `amount` bigint NOT NULL COMMENT '변동 금액. 충전 +, 사용·만료 − (ADR-04)',
  `group_id` bigint NOT NULL COMMENT '대상 포인트 그룹 식별자 (ADR-04)',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL COMMENT '발생 시각',
  `order_id` bigint DEFAULT NULL COMMENT '사용 이력의 주문 식별자. 충전·만료는 없음 (PNT-05)',
  `user_id` bigint NOT NULL COMMENT '사용자 식별자',
  `type` enum('CHARGE','EXPIRE','USE') COLLATE utf8mb4_general_ci NOT NULL COMMENT '이력 종류 — 충전·사용·만료 (ADR-04)',
  PRIMARY KEY (`id`),
  KEY `idx_point_histories_user` (`user_id`),
  KEY `idx_point_histories_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `payments` (
  `check_count` int NOT NULL COMMENT 'PG 조회 횟수',
  `amount` bigint NOT NULL COMMENT '결제 금액. 저장된 주문 합계',
  `completed_at` datetime(6) DEFAULT NULL COMMENT '종결 시각 (SUCCESS·FAILED)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_checked_at` datetime(6) DEFAULT NULL COMMENT '마지막 PG 조회 시각 (reconcile)',
  `next_check_at` datetime(6) DEFAULT NULL COMMENT '다음 PG 조회 예정 시각. PENDING·UNKNOWN만 있고 종결이면 없음 (plan.md 5-1)',
  `order_id` bigint NOT NULL COMMENT '주문 식별자. unique → 주문당 시도 1건 (plan.md 2)',
  `requested_at` datetime(6) NOT NULL COMMENT '시도 저장 시각 (TX A)',
  `sent_at` datetime(6) DEFAULT NULL COMMENT '발송 권한을 얻은 시각 (SENDING)',
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL COMMENT '결제한 사용자 식별자',
  `attempt_key` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '클라이언트 Idempotency-Key. unique (plan.md 2)',
  `failure_reason` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '실패 사유. PG 사유, NOT_SENT(보내지 않고 닫음, ADR-W5-03) 또는 EXPIRED(보낸 뒤 30분 미해결)',
  `last_check_result` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '마지막 조회 결과·이상 기록 (0건·복수·불일치·모순)',
  `provider` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PG 제공자 (예: LOCAL_PG)',
  `provider_order_id` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PG에 보내는 주문 식별자 LP-ORD-%010d. unique (plan.md 2)',
  `transaction_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'PG 거래 키. PG가 거래를 만든 뒤에만 있음',
  `pg_status` enum('FAILED','PENDING','SUCCESS') COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '마지막으로 관찰한 PG 거래 상태',
  `status` enum('FAILED','PENDING','REQUESTED','SENDING','SUCCESS','UNKNOWN') COLLATE utf8mb4_general_ci NOT NULL COMMENT '결제 상태 — REQUESTED·SENDING·PENDING·UNKNOWN·SUCCESS·FAILED (plan.md 3-2)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_order` (`order_id`),
  UNIQUE KEY `uk_payments_attempt_key` (`attempt_key`),
  UNIQUE KEY `uk_payments_provider_order` (`provider_order_id`),
  KEY `idx_payments_status_next_check` (`status`,`next_check_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `payment_outbox` (
  `claimed_at` datetime(6) DEFAULT NULL COMMENT '발송 권한을 얻은 시각',
  `completed_at` datetime(6) DEFAULT NULL COMMENT 'DONE·CLOSED가 된 시각',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_id` bigint NOT NULL COMMENT '결제 시도 식별자. unique → 시도당 발송 장부 1줄',
  `updated_at` datetime(6) NOT NULL,
  `event_type` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '장부 종류 — PAYMENT_REQUESTED',
  `status` enum('CLAIMED','CLOSED','DONE','PENDING') COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PENDING·CLAIMED·DONE·CLOSED (ADR-W5-01·03)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_outbox_payment` (`payment_id`),
  KEY `idx_payment_outbox_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `stock_reservations` (
  `quantity` int NOT NULL COMMENT '선점 수량, 1 이상 (ADR-W5-02)',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '선점한 주문 식별자 (ADR-W5-02)',
  `product_id` bigint NOT NULL COMMENT '선점한 상품 식별자. order_id와 쌍으로 unique (ADR-W5-02)',
  `updated_at` datetime(6) NOT NULL,
  `status` enum('CONSUMED','RELEASED','RESERVED') COLLATE utf8mb4_general_ci NOT NULL COMMENT 'RESERVED → CONSUMED | RELEASED (ADR-W5-02)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_reservations_order_product` (`order_id`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
