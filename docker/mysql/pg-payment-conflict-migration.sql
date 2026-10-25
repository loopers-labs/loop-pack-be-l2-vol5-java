-- 기존 PG DB는 애플리케이션 쓰기를 멈추고 신규 코드 기동 전에 적용한다.
-- 기존 충돌 기록을 보존하고 복수 거래 사유를 허용한다. 새 DB는 init.sql을 사용한다.
ALTER TABLE loopers.payment_attempts
    MODIFY COLUMN conflict_reason
        ENUM('RESULT_CONTRADICTION', 'TRANSACTION_MISMATCH', 'MULTIPLE_TRANSACTIONS') DEFAULT NULL;
