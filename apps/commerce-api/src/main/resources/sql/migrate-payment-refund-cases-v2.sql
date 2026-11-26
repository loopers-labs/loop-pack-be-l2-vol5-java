-- 환불 확인 대상 두 축 모델 이전 (10주차 5b 계획 6절)
-- 5단계 모양(status·reason 한 칸) payment_refund_cases를 돈의 상태(money_status)·담당(owner) 두 칸으로 옮긴다. 로컬 DB용이다(5단계는 미배포).
-- 실행 뒤 열·인덱스 모양은 create-payment-refund-cases.sql·PaymentRefundCaseJpaEntity와 같다.
-- MySQL 8에는 ADD/DROP COLUMN IF [NOT] EXISTS가 없어 information_schema에서 필요한 문장만 만들어 실행한다(add-payment-started-at.sql과 같은 방식).
-- 다시 실행하면 옛 status 열이 없으므로 아무것도 바꾸지 않는다.

-- 1. 새 칸을 NULL 허용으로 먼저 더한다(옛 행을 채운 뒤 NOT NULL로 바꾼다).
SET @add_refund_case_columns = (
    SELECT IFNULL(CONCAT('ALTER TABLE payment_refund_cases ',
               GROUP_CONCAT(CONCAT('ADD COLUMN ', d.column_name, ' ', d.definition) ORDER BY d.seq SEPARATOR ', ')), 'DO 0')
    FROM (
        SELECT 1 AS seq, 'money_status' AS column_name, 'VARCHAR(30) NULL AFTER amount' AS definition
        UNION ALL SELECT 2, 'refund_actor', 'VARCHAR(20) NULL AFTER money_status'
        UNION ALL SELECT 3, 'owner', 'VARCHAR(20) NULL AFTER refund_actor'
        UNION ALL SELECT 4, 'owner_reason', 'VARCHAR(500) NULL AFTER owner'
        UNION ALL SELECT 5, 'owner_changed_at', 'DATETIME(6) NULL AFTER owner_reason'
        UNION ALL SELECT 6, 'owner_closed_at', 'DATETIME(6) NULL AFTER owner_changed_at'
        UNION ALL SELECT 7, 'owner_closed_by', 'VARCHAR(100) NULL AFTER owner_closed_at'
    ) d
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.columns c
        WHERE c.table_schema = DATABASE()
          AND c.table_name = 'payment_refund_cases'
          AND c.column_name = d.column_name
    )
);
PREPARE add_refund_case_columns FROM @add_refund_case_columns;
EXECUTE add_refund_case_columns;
DEALLOCATE PREPARE add_refund_case_columns;

-- 2. 옛 status가 남아 있을 때만 옮긴다(계획 6절 매핑).
--    HANDED_TO_HUMAN은 담당만 사람이다. 돈은 결과 뒤집힘·불일치·복수 거래·취소 거절이면 청구 확인, 기한 이관이면 요청 여부로 나눈다.
--    옛 모델은 이관을 결론으로 적었으므로 그 시각(concluded_at)을 담당이 바뀐 시각과 다음 조회 시각으로 옮기고 결론 시각은 비운다.
--    MySQL UPDATE는 SET을 왼쪽부터 적용하므로 concluded_at을 비우는 칸을 마지막에 둔다.
SET @map_refund_case_status = (
    SELECT IF(COUNT(*) = 0, 'DO 0', CONCAT(
        'UPDATE payment_refund_cases SET ',
        'money_status = CASE status',
        ' WHEN ''TRACKING'' THEN ''CHECKING''',
        ' WHEN ''REFUND_REQUESTED'' THEN ''REFUND_REQUESTED''',
        ' WHEN ''REFUND_UNKNOWN'' THEN ''REFUND_UNKNOWN''',
        ' WHEN ''REFUNDED'' THEN ''REFUNDED''',
        ' WHEN ''CLOSED_NO_CHARGE'' THEN ''NO_CHARGE''',
        ' WHEN ''HANDED_TO_HUMAN'' THEN CASE WHEN reason = ''HANDOFF_DEADLINE''',
        ' THEN IF(refund_requested_at IS NULL, ''CHECKING'', ''REFUND_UNKNOWN'') ELSE ''CHARGED'' END END, ',
        'refund_actor = IF(status = ''REFUNDED'', ''SYSTEM'', NULL), ',
        'owner = IF(status = ''HANDED_TO_HUMAN'', ''PERSON'', ''AUTO''), ',
        'owner_reason = IF(status = ''HANDED_TO_HUMAN'', reason, NULL), ',
        'owner_changed_at = IF(status = ''HANDED_TO_HUMAN'', concluded_at, NULL), ',
        'next_check_at = IF(status = ''HANDED_TO_HUMAN'', concluded_at, next_check_at), ',
        'concluded_at = IF(status = ''HANDED_TO_HUMAN'', NULL, concluded_at) ',
        'WHERE money_status IS NULL'))
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund_cases'
      AND column_name = 'status'
);
PREPARE map_refund_case_status FROM @map_refund_case_status;
EXECUTE map_refund_case_status;
DEALLOCATE PREPARE map_refund_case_status;

-- 3. 채운 두 칸을 엔티티처럼 NOT NULL로 바꾼다. 옮기지 못한 옛 값이 있으면 여기서 실패해 드러난다.
SET @require_refund_case_axes = (
    SELECT IFNULL(CONCAT('ALTER TABLE payment_refund_cases ',
               GROUP_CONCAT(CONCAT('MODIFY COLUMN ', column_name, ' VARCHAR(', character_maximum_length, ') NOT NULL')
                   ORDER BY column_name SEPARATOR ', ')), 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund_cases'
      AND column_name IN ('money_status', 'owner')
      AND is_nullable = 'YES'
);
PREPARE require_refund_case_axes FROM @require_refund_case_axes;
EXECUTE require_refund_case_axes;
DEALLOCATE PREPARE require_refund_case_axes;

-- 4. 옛 인덱스를 먼저 지운다(status 열만 지우면 next_check_at만 남은 옛 이름 인덱스가 남는다).
SET @drop_old_refund_case_index = (
    SELECT IF(COUNT(*) = 0, 'DO 0', 'DROP INDEX idx_payment_refund_cases_status_next_check ON payment_refund_cases')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund_cases'
      AND index_name = 'idx_payment_refund_cases_status_next_check'
);
PREPARE drop_old_refund_case_index FROM @drop_old_refund_case_index;
EXECUTE drop_old_refund_case_index;
DEALLOCATE PREPARE drop_old_refund_case_index;

-- 5. 옛 칸을 지운다.
SET @drop_old_refund_case_columns = (
    SELECT IFNULL(CONCAT('ALTER TABLE payment_refund_cases ',
               GROUP_CONCAT(CONCAT('DROP COLUMN ', column_name) ORDER BY column_name SEPARATOR ', ')), 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund_cases'
      AND column_name IN ('status', 'reason')
);
PREPARE drop_old_refund_case_columns FROM @drop_old_refund_case_columns;
EXECUTE drop_old_refund_case_columns;
DEALLOCATE PREPARE drop_old_refund_case_columns;

-- 6. 대상 선택(돈의 상태 비종결 + 다음 조회 시각)용 인덱스를 둔다.
SET @add_refund_case_index = (
    SELECT IF(COUNT(*) = 0,
               'CREATE INDEX idx_payment_refund_cases_money_status_next_check ON payment_refund_cases (money_status, next_check_at)',
               'DO 0')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund_cases'
      AND index_name = 'idx_payment_refund_cases_money_status_next_check'
);
PREPARE add_refund_case_index FROM @add_refund_case_index;
EXECUTE add_refund_case_index;
DEALLOCATE PREPARE add_refund_case_index;
