-- 6주차 최초 PG 확정 시각 열. 5주차 POINT→PG 전환(db/pg-migration)과 별개의 단계다.
-- 보존할 실데이터가 없다는 사용자 확인에 따라 backfill 하지 않으며 기존 행은 NULL 로 남는다.
ALTER TABLE orders
    ADD COLUMN confirmed_at DATETIME(6) NULL;
