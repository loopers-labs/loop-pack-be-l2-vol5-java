-- relay 잠금 강제 해제 (docs/week7/operations.md 3-2). 잠금을 쥔 실행이 확실히 죽었을 때만.
-- 행을 DELETE하지 않는다 — 앱이 도는 중 행을 지우면 ShedLock이 UPDATE만 시도해 재시작 전까지 잠금을 못 얻는다.
UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'order-outbox-relay';
