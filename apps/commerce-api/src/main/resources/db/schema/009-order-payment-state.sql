-- 001~008 적용 DB에서 쓰기를 멈춘 뒤 한 번 적용한다. MySQL DDL은 암묵적으로 커밋된다.
-- 기존 결제 금액·확정/감사 시각·품목 스냅샷·재고·포인트는 변경하지 않는다.
ALTER TABLE `order`
    MODIFY COLUMN `status` ENUM('DRAFT', 'CONFIRMED', 'PAYMENT_PENDING', 'PAYMENT_FAILED') NOT NULL,
    ADD COLUMN `payment_method` ENUM('POINT', 'PG') NULL AFTER `status`;

-- 이행 전 CONFIRMED는 기존 POINT 결제 사실이다. DRAFT에는 수단을 미리 선택하지 않는다.
UPDATE `order`
SET `payment_method` = 'POINT'
WHERE `status` = 'CONFIRMED' AND `payment_method` IS NULL;

-- CHECK는 UNKNOWN도 허용하므로, 필수 수단·금액·시각의 NULL 여부를 명시한다.
ALTER TABLE `order`
    DROP CHECK `ck_order_payment_state`,
    ADD CONSTRAINT `ck_order_payment_state` CHECK (
        (`status` = 'DRAFT' AND `payment_method` IS NULL
            AND `paid_amount` IS NULL AND `confirmed_at` IS NULL)
        OR (`status` IN ('PAYMENT_PENDING', 'PAYMENT_FAILED')
            AND `payment_method` IS NOT NULL AND `payment_method` = 'PG'
            AND `paid_amount` IS NULL AND `confirmed_at` IS NULL)
        OR (`status` = 'CONFIRMED'
            AND `payment_method` IS NOT NULL AND `payment_method` IN ('POINT', 'PG')
            AND `paid_amount` IS NOT NULL AND `paid_amount` = `total_amount`
            AND `confirmed_at` IS NOT NULL)
    );

-- 과거 POINT의 재고 차감 사실을 W5 예약으로 꾸미지 않는다.
ALTER TABLE `order_item`
    ADD COLUMN `reservation_status` ENUM('NOT_RESERVED', 'HELD', 'CONSUMED', 'RELEASED')
        NOT NULL DEFAULT 'NOT_RESERVED' AFTER `quantity`;
