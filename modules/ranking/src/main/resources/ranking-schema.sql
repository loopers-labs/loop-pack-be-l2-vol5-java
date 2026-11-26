-- 주간 구매 랭킹 (docs/week9/design.md 3절). 시각은 모두 UTC DATETIME(6)이다.

-- snapshot 정보: 기간·revision·상태와 고정한 입력의 건수·checksum
CREATE TABLE IF NOT EXISTS ranking_snapshot (
    snapshot_id     VARCHAR(64) NOT NULL,
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    revision        INT         NOT NULL,
    status          VARCHAR(20) NOT NULL,
    frozen_at       DATETIME(6) NOT NULL,
    input_count     INT         NOT NULL,
    product_count   INT         NOT NULL,
    input_checksum  CHAR(64)    NOT NULL,
    result_checksum CHAR(64)    NULL,
    completed_at    DATETIME(6) NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_ranking_snapshot_period_revision (period_start, revision)
) ENGINE = InnoDB;

-- snapshot 입력: 고정 시점의 구매 활동 복사본. 고정 후 바뀌지 않는다
CREATE TABLE IF NOT EXISTS ranking_snapshot_input (
    snapshot_id  VARCHAR(64) NOT NULL,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY idx_ranking_snapshot_input_product (snapshot_id, product_id)
) ENGINE = InnoDB;

-- 점수 stage: 계산 중 상품별 절대 점수. API는 읽지 않는다
CREATE TABLE IF NOT EXISTS ranking_score_stage (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL,
    score       BIGINT      NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
) ENGINE = InnoDB;

-- 완성 ranking: 공개 단계에서 한 번에 만든 점수·계산 순위
CREATE TABLE IF NOT EXISTS weekly_ranking (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL,
    score       BIGINT      NOT NULL,
    rank_no     INT         NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    KEY idx_weekly_ranking_rank (snapshot_id, rank_no)
) ENGINE = InnoDB;

-- 공개 pointer: 기간마다 고객에게 보여 줄 snapshot 하나
CREATE TABLE IF NOT EXISTS ranking_publication (
    period_start DATE        NOT NULL,
    snapshot_id  VARCHAR(64) NOT NULL,
    revision     INT         NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (period_start)
) ENGINE = InnoDB;
