-- 주간 랭킹 테이블 (docs/week9/commerce-ranking.md). 정의는 이 파일 한 벌이다(D155). 완성 랭킹·공개 pointer는 api도 읽으며,
-- api 테스트는 이 파일을 빌드 때 복사해 그대로 실행한다.
-- ddl-auto를 쓰지 않는 환경에서 주간 랭킹 Job을 처음 실행하기 전에 실행한다. 없을 때만 만들어 다시 실행해도 결과가 같다.
-- local·test 프로필은 시작할 때 자동 실행한다(spring.sql.init).

-- snapshot 정보. 상태 칸이 없다(D151): 이 행이 있으면 고정된 것이다. 구간 끝은 period_start + 7일로 계산한다(D159).
CREATE TABLE IF NOT EXISTS ranking_snapshot (
    snapshot_id VARCHAR(100) NOT NULL,
    period_start DATE NOT NULL,
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

-- 완성 랭킹(D156·D157). calc_rank는 삭제 여부와 무관한 계산 순위다. 상위 K까지 저장하고 확장은 최대 calc_rank 다음부터 추가만 한다.
CREATE TABLE IF NOT EXISTS ranking_result (
    snapshot_id VARCHAR(100) NOT NULL,
    calc_rank INT NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, calc_rank),
    CONSTRAINT uk_ranking_result_snapshot_product UNIQUE (snapshot_id, product_id)
);

-- 공개 pointer. 주마다 고객에게 보일 snapshot 하나를 가리킨다. revision은 두지 않고 snapshot 정보와 조인해 비교한다.
CREATE TABLE IF NOT EXISTS ranking_publication (
    period_start DATE NOT NULL,
    snapshot_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (period_start)
);
