-- 관찰 SQL 확인용 데이터 (verify.sh만 쓴다). 상태마다 기대 결과가 분명하도록 시각을 UTC_TIMESTAMP 기준으로 둔다.
-- consumer 테이블 두 개는 commerce-streamer 엔티티(ConsumedOrderConfirmation · ProductPurchaseActivity)의 컬럼을 옮겨 적었다.
CREATE TABLE consumed_order_confirmation (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, created_at datetime(6) NOT NULL, updated_at datetime(6) NOT NULL, deleted_at datetime(6),
  event_id varchar(36) NOT NULL, order_id bigint NOT NULL, fingerprint varchar(64) NOT NULL, payload text NOT NULL,
  UNIQUE KEY uk_consumed_confirmation_event (event_id), UNIQUE KEY uk_consumed_confirmation_order (order_id));
CREATE TABLE product_purchase_activity (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, created_at datetime(6) NOT NULL, updated_at datetime(6) NOT NULL, deleted_at datetime(6),
  order_id bigint NOT NULL, product_id bigint NOT NULL, quantity int NOT NULL, confirmed_at datetime(6) NOT NULL, event_id varchar(36) NOT NULL,
  UNIQUE KEY uk_purchase_activity_order_product (order_id, product_id));

SET @now = UTC_TIMESTAMP(6);
INSERT INTO order_outbox (event_id, order_id, event_type, schema_version, message_key, payload, status, attempt_count,
                          next_attempt_at, last_error, published_at, published_partition, published_offset, created_at, updated_at) VALUES
-- e-01 막 확정된 PENDING (차례 됨)
('e-01', 1, 'OrderConfirmed', 1, '1', '{}', 'PENDING',   0, @now - INTERVAL 5 SECOND, NULL, NULL, NULL, NULL, @now - INTERVAL 5 SECOND, @now),
-- e-02 1번 실패, backoff 대기 중 (아직 차례 아님)
('e-02', 2, 'OrderConfirmed', 1, '2', '{}', 'PENDING',   1, @now + INTERVAL 20 SECOND, 'TimeoutException: Topic order-events not present in metadata', NULL, NULL, NULL, @now - INTERVAL 40 SECOND, @now),
-- e-03 보내는 중, lease 안 (차례 아님)
('e-03', 3, 'OrderConfirmed', 1, '3', '{}', 'SENDING',   0, @now + INTERVAL 50 SECOND, NULL, NULL, NULL, NULL, @now - INTERVAL 10 SECOND, @now),
-- e-04 ack 뒤 저장 실패로 SENDING에 남음, lease 지남 (차례 됨)
('e-04', 4, 'OrderConfirmed', 1, '4', '{}', 'SENDING',   0, @now - INTERVAL 3 MINUTE, NULL, NULL, NULL, NULL, @now - INTERVAL 4 MINUTE, @now),
-- e-05 3번 실패 → BLOCKED
('e-05', 5, 'OrderConfirmed', 1, '5', '{}', 'BLOCKED',   3, @now - INTERVAL 1 MINUTE, 'TimeoutException: Expiring 1 record(s) for order-events-2', NULL, NULL, NULL, @now - INTERVAL 3 MINUTE, @now - INTERVAL 1 MINUTE),
-- e-06 PUBLISHED + 소비 완료
('e-06', 6, 'OrderConfirmed', 1, '6', '{}', 'PUBLISHED', 0, @now - INTERVAL 10 MINUTE, NULL, @now - INTERVAL 10 MINUTE, 0, 10, @now - INTERVAL 10 MINUTE, @now),
-- e-07 PUBLISHED인데 5분째 소비 완료 기록 없음 (consumer 지연·DLT 의심)
('e-07', 7, 'OrderConfirmed', 1, '7', '{}', 'PUBLISHED', 0, @now - INTERVAL 5 MINUTE, NULL, @now - INTERVAL 5 MINUTE, 1, 11, @now - INTERVAL 5 MINUTE, @now),
-- e-08 방금 PUBLISHED, 아직 소비 전 (정상 — O-6에 나오면 안 됨)
('e-08', 8, 'OrderConfirmed', 1, '8', '{}', 'PUBLISHED', 0, @now - INTERVAL 2 SECOND, NULL, @now - INTERVAL 2 SECOND, 2, 12, @now - INTERVAL 2 SECOND, @now),
-- e-09 8일 전 PUBLISHED + 소비 완료 (보존 7일 지남 → 삭제 대상)
('e-09', 9, 'OrderConfirmed', 1, '9', '{}', 'PUBLISHED', 0, @now - INTERVAL 8 DAY, NULL, @now - INTERVAL 8 DAY, 0, 1, @now - INTERVAL 8 DAY, @now - INTERVAL 8 DAY),
-- e-10 8일 전 BLOCKED (미해결 — 오래돼도 삭제하면 안 됨)
('e-10', 10, 'OrderConfirmed', 1, '10', '{}', 'BLOCKED', 3, @now - INTERVAL 8 DAY, 'old failure', NULL, NULL, NULL, @now - INTERVAL 8 DAY, @now - INTERVAL 8 DAY),
-- e-11 8일 전 PENDING (미발행 — 오래돼도 삭제하면 안 됨)
('e-11', 11, 'OrderConfirmed', 1, '11', '{}', 'PENDING', 2, @now - INTERVAL 8 DAY, 'old failure', NULL, NULL, NULL, @now - INTERVAL 8 DAY, @now - INTERVAL 8 DAY);

INSERT INTO consumed_order_confirmation (created_at, updated_at, event_id, order_id, fingerprint, payload) VALUES
(@now, @now, 'e-06', 6, 'f6', '{}'), (@now, @now, 'e-09', 9, 'f9', '{}');
INSERT INTO product_purchase_activity (created_at, updated_at, order_id, product_id, quantity, confirmed_at, event_id) VALUES
(@now, @now, 6, 7, 2, @now, 'e-06'), (@now, @now, 6, 8, 1, @now, 'e-06');

-- relay 잠금: 지금 누가 쥐고 있음
INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES ('order-outbox-relay', UTC_TIMESTAMP(3) + INTERVAL 4 MINUTE, UTC_TIMESTAMP(3), 'host-a');
