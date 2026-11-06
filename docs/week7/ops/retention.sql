-- 보존 정리 (수동 SQL, docs/week7/operations.md 4절). 삭제 job은 만들지 않았다.
-- PUBLISHED이고 broker 확인 뒤 7일이 지난 행만 지운다. PENDING·SENDING·BLOCKED는 몇 날이 지나도 지우지 않는다 (미발행 사실).
-- 큰 테이블에서 잠금을 짧게 하려고 1,000행씩 반복한다 (영향 행 0이 될 때까지).
DELETE FROM order_outbox
WHERE status = 'PUBLISHED' AND published_at < UTC_TIMESTAMP(6) - INTERVAL 7 DAY
ORDER BY id LIMIT 1000;
