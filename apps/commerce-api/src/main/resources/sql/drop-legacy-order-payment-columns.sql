-- 주문의 옛 결제 열 삭제 (설계 22절)
-- 실행 순서: migrate-point-payments.sql → verify-point-payment-migration.sql → 이 스크립트. 검증이 통과하지 않으면 실행하지 않는다.
-- 삭제 후에는 이관 스크립트를 실행할 수 없다(옛 열을 읽는다).
-- MySQL 8에는 DROP COLUMN IF EXISTS가 없어 information_schema에서 남은 열만 골라 지운다. 다시 실행하면 아무것도 하지 않는다.
SET @drop_legacy_order_payment_columns = (
    SELECT IFNULL(CONCAT('ALTER TABLE orders ',
               GROUP_CONCAT(CONCAT('DROP COLUMN ', column_name) ORDER BY column_name SEPARATOR ', ')), 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name IN ('paid_amount', 'payment_result')
);
PREPARE drop_legacy_order_payment_columns FROM @drop_legacy_order_payment_columns;
EXECUTE drop_legacy_order_payment_columns;
DEALLOCATE PREPARE drop_legacy_order_payment_columns;
