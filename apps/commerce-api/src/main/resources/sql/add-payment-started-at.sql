-- 결제 기록 시작 시각 열·인덱스 추가와 기존 결제 대기 보정 (설계 26절)
-- ddl-auto를 쓰지 않는 환경에서 결제 만료 코드를 배포하기 전에 실행한다. 열·인덱스 모양은 PaymentRecordJpaEntity와 같다.
-- MySQL 8에는 ADD COLUMN IF NOT EXISTS가 없어 information_schema에서 없을 때만 추가한다. 다시 실행해도 결과가 같다.
SET @add_payment_started_at = (
    SELECT IF(COUNT(*) = 0, 'ALTER TABLE payment_records ADD COLUMN started_at DATETIME(6) NULL', 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_records'
      AND column_name = 'started_at'
);
PREPARE add_payment_started_at FROM @add_payment_started_at;
EXECUTE add_payment_started_at;
DEALLOCATE PREPARE add_payment_started_at;

SET @add_payment_started_at_index = (
    SELECT IF(COUNT(*) = 0,
               'CREATE INDEX idx_payment_records_result_started_at ON payment_records (result, started_at)', 'DO 0')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_records'
      AND index_name = 'idx_payment_records_result_started_at'
);
PREPARE add_payment_started_at_index FROM @add_payment_started_at_index;
EXECUTE add_payment_started_at_index;
DEALLOCATE PREPARE add_payment_started_at_index;

-- 스케줄 행은 트랜잭션 A에서 결제 기록과 같은 시각으로 만들어지므로 created_at이 결제 시작 시각이다.
-- 확인 필요가 되어 스케줄 행이 지워진 결제 대기는 근거가 없어 비워 두며, 만료 대상이 아니므로 사람이 처리한다.
-- 이미 채운 행은 건너뛰어 다시 실행해도 값이 바뀌지 않는다.
UPDATE payment_records p
JOIN payment_lookup_schedules s ON s.order_id = p.order_id
SET p.started_at = s.created_at
WHERE p.method = 'PG'
  AND p.result = 'PENDING'
  AND p.started_at IS NULL;
