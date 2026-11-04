-- 현재 코드의 기존 order_records가 있는 DB에 한 번 적용한다.
-- 기존 행은 금액·PAID·시각·품목을 바꾸지 않고 POINT로 보존한다.
USE loopers;
ALTER TABLE order_records
    ADD COLUMN payment_method ENUM('POINT', 'PG') NOT NULL DEFAULT 'POINT';
