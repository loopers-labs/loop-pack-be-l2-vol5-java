-- 이미 생성된 loopers 실습 DB에 PG 결제를 켜기 전 한 번 적용한다. 주문 결제수단 추가와 결제 시도 테이블 생성을 함께 적용한다.
-- 새 DB의 테이블 생성에는 init.sql을 사용한다.
ALTER TABLE loopers.orders
    MODIFY status ENUM('CONFIRMED', 'DRAFT', 'PAYMENT_FAILED', 'PAYMENT_PENDING') DEFAULT NULL,
    MODIFY payment_result ENUM('FAILED', 'SUCCESS') DEFAULT NULL,
    ADD COLUMN payment_method ENUM('PG', 'POINT') DEFAULT NULL;

-- 결제수단을 저장하기 전에 확정된 주문은 모두 포인트로 결제했다. 결제액·결제 결과·품목은 바꾸지 않는다.
UPDATE loopers.orders
SET payment_method = 'POINT'
WHERE status = 'CONFIRMED'
  AND payment_method IS NULL;

-- PG 결제 시도를 저장한다. 시도 키·주문 ID·PG 주문 식별자는 각각 유일하다.
CREATE TABLE IF NOT EXISTS loopers.payment_attempts (
    amount BIGINT NOT NULL,
    conflict_detected_at DATETIME(6) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    last_checked_at DATETIME(6) DEFAULT NULL,
    order_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    payment_attempt_key VARCHAR(36) NOT NULL,
    provider_order_id VARCHAR(64) NOT NULL,
    transaction_key VARCHAR(255) DEFAULT NULL,
    conflict_reason ENUM('RESULT_CONTRADICTION', 'TRANSACTION_MISMATCH', 'MULTIPLE_TRANSACTIONS') DEFAULT NULL,
    status ENUM('FAILED', 'PENDING', 'REQUESTED', 'SUCCEEDED', 'UNKNOWN') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_attempts_key (payment_attempt_key),
    UNIQUE KEY uk_payment_attempts_order_id (order_id),
    UNIQUE KEY uk_payment_attempts_provider_order_id (provider_order_id),
    KEY idx_payment_attempts_status_checked (status, last_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
