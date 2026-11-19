-- 주간 랭킹 배치를 처음 실행하기 전에 기존 DB에 한 번 적용한다. 새 DB는 init.sql을 사용한다. 반복 실행해도 결과가 같다.
-- commerce-batch가 쓰고 commerce-api가 완성된 회차만 읽는다. 계산 중인 회차는 결과 checksum과 계산 순위가 비어 있다.
-- purchase-activity-migration.sql을 먼저 적용해 product_purchase_activity가 있어야 한다.
CREATE TABLE IF NOT EXISTS loopers.weekly_ranking_revisions (
    period_start DATE NOT NULL,
    revision INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    result_checksum VARCHAR(64) DEFAULT NULL,
    job_execution_id BIGINT NOT NULL,
    cancelled_at DATETIME(6) DEFAULT NULL,
    cancel_reason VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (period_start, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS loopers.weekly_ranking_scores (
    period_start DATE NOT NULL,
    revision INT NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    rank_no INT DEFAULT NULL,
    PRIMARY KEY (period_start, revision, product_id),
    UNIQUE KEY uk_weekly_ranking_scores_revision_rank (period_start, revision, rank_no),
    CONSTRAINT ck_weekly_ranking_scores_score CHECK (score > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 점수 고정이 기간 범위로 읽도록 구매 활동에 확정 시각 인덱스를 둔다. 이미 있으면 만들지 않는다.
SET @purchase_activity_confirmed_at_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = 'loopers' AND table_name = 'product_purchase_activity'
      AND index_name = 'idx_purchase_activity_confirmed_at'
);
SET @purchase_activity_confirmed_at_index_ddl = IF(@purchase_activity_confirmed_at_index_exists = 0,
    'CREATE INDEX idx_purchase_activity_confirmed_at ON loopers.product_purchase_activity (confirmed_at)', 'SELECT 1');
PREPARE purchase_activity_confirmed_at_index FROM @purchase_activity_confirmed_at_index_ddl;
EXECUTE purchase_activity_confirmed_at_index;
DEALLOCATE PREPARE purchase_activity_confirmed_at_index;
