-- 기존 주문 상태와 기록을 보존하며 PG 결제 대기/실패 상태를 추가한다. 한 번만 적용한다.
USE loopers;
ALTER TABLE orders
    MODIFY COLUMN status ENUM('CONFIRMED', 'DRAFT', 'PAYMENT_PENDING', 'PAYMENT_FAILED')
        CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;
