-- W9 주간 구매 랭킹 (설계 10장, TB-19 ~ TB-23). MySQL 8.0.19 이상 (upsert 행 별칭, window 함수).
-- 쓰기는 commerce-batch 의 weeklyRanking job 만, 읽기는 commerce-api 의 GET /api/v1/rankings/weekly.
-- 엔티티가 없는 테이블이라 ddl-auto 로 만들어지지 않는다. 배포·테스트 모두 이 파일을 실행한다 (여러 번 실행해도 같다).
-- 행은 지우지 않는다. 모든 시각은 UTC 값이다.

-- TB-19 snapshot 정보 (manifest). snapshot 하나 = 기간 하나의 revision 하나.
CREATE TABLE IF NOT EXISTS ranking_snapshot (
    snapshot_id               VARCHAR(64) NOT NULL COMMENT 'weekly:{periodStart}:r{revision}',
    period_start              DATE        NOT NULL COMMENT 'UTC 월요일 (포함)',
    period_end                DATE        NOT NULL COMMENT '다음 UTC 월요일 (제외)',
    revision                  BIGINT      NOT NULL COMMENT '기간별 1 부터 1 씩',
    status                    VARCHAR(20) NOT NULL COMMENT 'FROZEN | PUBLISHED | SUPERSEDED',
    frozen_at                 DATETIME(6) NOT NULL COMMENT '입력 고정 시각',
    input_count               INT         NOT NULL COMMENT '고정한 구매 활동 행 수',
    product_count             INT         NOT NULL COMMENT '고정한 입력의 서로 다른 상품 수 = 완성 점수 행 수',
    input_checksum            CHAR(64)    NOT NULL COMMENT 'SHA-256 hex (orderId, productId 순 TAB/LF 직렬화)',
    result_checksum           CHAR(64)    NULL     COMMENT 'SHA-256 hex (rank 순 TAB/LF 직렬화). 공개 단계에서',
    published_at              DATETIME(6) NULL     COMMENT '공개 단계를 마친 시각 (PUBLISHED·SUPERSEDED)',
    frozen_by_execution_id    BIGINT      NOT NULL COMMENT '입력을 고정한 JobExecution (소속 증거, 내용 비교에서 제외)',
    published_by_execution_id BIGINT      NULL     COMMENT '공개 단계를 마친 JobExecution',
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY ux_ranking_snapshot_period_revision (period_start, revision)
) ENGINE = InnoDB;

-- TB-20 snapshot 입력. 고정 뒤 바뀌지 않는다.
CREATE TABLE IF NOT EXISTS ranking_snapshot_input (
    snapshot_id  VARCHAR(64) NOT NULL,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY ix_ranking_snapshot_input_product (snapshot_id, product_id)
) ENGINE = InnoDB;

-- TB-21 점수 stage. 계산 중 상품별 절대 점수 (upsert).
CREATE TABLE IF NOT EXISTS ranking_score_stage (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL,
    score       BIGINT      NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
) ENGINE = InnoDB;

-- TB-22 완성 ranking. 공개 단계가 stage 에서 한 번에 만든다. rank 는 MySQL 예약어라 rank_no.
CREATE TABLE IF NOT EXISTS weekly_ranking (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL,
    score       BIGINT      NOT NULL,
    rank_no     INT         NOT NULL COMMENT 'score DESC, product_id ASC 순번 (1 부터, 동점도 다른 순위)',
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY ux_weekly_ranking_rank (snapshot_id, rank_no)
) ENGINE = InnoDB;

-- TB-23 공개 pointer. 기간별 공개 snapshot 하나. revision 이 커지는 방향으로만 바뀐다.
CREATE TABLE IF NOT EXISTS weekly_ranking_publication (
    period_start DATE        NOT NULL,
    snapshot_id  VARCHAR(64) NOT NULL,
    revision     BIGINT      NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (period_start)
) ENGINE = InnoDB;
