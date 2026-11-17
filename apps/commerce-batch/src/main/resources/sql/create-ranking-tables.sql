-- 주간 랭킹 snapshot 테이블 (docs/week9/commerce-ranking.md). batch 전용이다.
-- ddl-auto를 쓰지 않는 환경에서 주간 랭킹 Job을 처음 실행하기 전에 실행한다. 없을 때만 만들어 다시 실행해도 결과가 같다.
-- local·test 프로필은 시작할 때 자동 실행한다(spring.sql.init).

-- snapshot 정보. 상태 칸이 없다(D151): 이 행이 있으면 고정된 것이다.
CREATE TABLE IF NOT EXISTS ranking_snapshot (
    snapshot_id VARCHAR(100) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision INT NOT NULL,
    frozen_at DATETIME(6) NOT NULL,
    input_count INT NOT NULL,
    input_checksum CHAR(64) NOT NULL,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT uk_ranking_snapshot_period_revision UNIQUE (period_start, revision)
);

-- snapshot 입력(원본 product_purchase_activity의 복사본). 고정 뒤 UPDATE·DELETE하지 않는다(D148).
CREATE TABLE IF NOT EXISTS ranking_snapshot_input (
    snapshot_id VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id)
);

-- 상품별 점수 stage(D150). writer가 절대값으로 upsert한다.
CREATE TABLE IF NOT EXISTS ranking_score_stage (
    snapshot_id VARCHAR(100) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);
