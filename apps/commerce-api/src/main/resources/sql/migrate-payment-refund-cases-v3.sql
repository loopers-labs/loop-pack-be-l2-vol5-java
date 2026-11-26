-- 환불 확인 대상에 마지막 확인 칸(last_check_at·last_check_outcome·last_check_cause)을 더한다 (10주차 5c)
-- migrate-payment-refund-cases-v2.sql 뒤에 실행한다. 실행 뒤 열 모양은 create-payment-refund-cases.sql·PaymentRefundCaseJpaEntity와 같다.
-- MySQL 8에는 ADD COLUMN IF NOT EXISTS가 없어 information_schema에서 없는 칸만 더하는 문장을 만들어 실행한다(v2와 같은 방식).
-- 기존 행의 세 칸은 비어 있고(아직 확인 기록 없음), 다시 실행하면 아무것도 바꾸지 않는다.
SET @add_refund_case_check_columns = (
    SELECT IFNULL(CONCAT('ALTER TABLE payment_refund_cases ',
               GROUP_CONCAT(CONCAT('ADD COLUMN ', d.column_name, ' ', d.definition) ORDER BY d.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'last_check_at' AS column_name, 'DATETIME(6) NULL AFTER concluded_at' AS definition
        UNION ALL SELECT 2, 'last_check_outcome', 'VARCHAR(30) NULL AFTER last_check_at'
        UNION ALL SELECT 3, 'last_check_cause', 'VARCHAR(100) NULL AFTER last_check_outcome'
    ) d
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.columns c
        WHERE c.table_schema = DATABASE()
          AND c.table_name = 'payment_refund_cases'
          AND c.column_name = d.column_name
    )
);
PREPARE add_refund_case_check_columns FROM @add_refund_case_check_columns;
EXECUTE add_refund_case_check_columns;
DEALLOCATE PREPARE add_refund_case_check_columns;
