-- W7 새 테이블 DDL (docs/week7/plan.md D-0). local 프로필은 ddl-auto: none이라 로컬 DB(loopers)에 한 번 실행한다.
-- 출처: 테스트(ddl-auto: create)가 loopers_test에 만든 테이블의 SHOW CREATE TABLE (2026-11-04).
-- 인덱스 확인 (11-04, 5만 행 중 PENDING 100): status IN ('PENDING','SENDING') AND next_attempt_at <= now ORDER BY id LIMIT 200
--   → type=range, key=idx_order_outbox_due, rows=101, Using index condition; Using filesort (차례가 된 행만 정렬)
--
-- W6 event_outbox는 W7에서 쓰지 않는다 (2단계, 11-06). 남은 미발행 행을 order_outbox로 옮기지 않고 버리기로 했다(학습자 결정).
-- 로컬 DB(loopers)에서 한 번 실행한다. 테스트 DB는 ddl-auto: create라 저절로 없어진다.
DROP TABLE IF EXISTS `event_outbox`;

CREATE TABLE `order_outbox` (
  `attempt_count` int NOT NULL COMMENT '연속 실패 횟수. 3이면 BLOCKED, 재개하면 0',
  `published_partition` int DEFAULT NULL COMMENT 'broker가 알려 준 partition — 재발행되면 마지막 값',
  `schema_version` int NOT NULL COMMENT 'payload 스키마 버전 — consumer가 해석 방법을 고른다',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `next_attempt_at` datetime(6) NOT NULL COMMENT 'PENDING: 다음 시도 가능 시각 · SENDING: 맡은 기한(lease). 지나면 relay가 고른다',
  `order_id` bigint NOT NULL COMMENT '확정된 주문 ID',
  `published_at` datetime(6) DEFAULT NULL COMMENT 'broker 확인(ack)을 기록한 시각',
  `published_offset` bigint DEFAULT NULL COMMENT 'broker가 알려 준 offset — 재발행되면 마지막 값',
  `updated_at` datetime(6) NOT NULL,
  `event_id` varchar(36) COLLATE utf8mb4_general_ci NOT NULL COMMENT '이벤트 신원(UUID). 최초 확정 TX에서 한 번 만들고, 재발행해도 같은 값 (ADR-W6-01)',
  `event_type` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '이벤트 종류 — OrderConfirmed',
  `message_key` varchar(200) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'partition을 고르는 key — 주문 ID 문자열. 같은 주문은 같은 partition',
  `last_error` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '마지막 send 실패 원인 — 진단용. 재개해도 지우지 않는다',
  `payload` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '보낼 JSON 원문. 생성 뒤 바꾸지 않는다. MySQL JSON 타입은 키 순서·공백을 다시 만들어 원문이 보존되지 않아 TEXT',
  `status` enum('BLOCKED','PENDING','PUBLISHED','SENDING') COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PENDING → SENDING → PUBLISHED | (실패) PENDING · 3번째 실패 BLOCKED → (재개) PENDING',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_outbox_event_id` (`event_id`),
  KEY `idx_order_outbox_due` (`status`,`next_attempt_at`,`id`),
  KEY `idx_order_outbox_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;
