-- 9주차 주간 구매 랭킹 테이블 4개 (설계 weekly-ranking-design.md 3절 D2~D5 의 DDL 그대로). 정의는 commerce-batch 엔티티가 소유하고 commerce-api 는 JDBC 로 읽기만 한다.
-- local/test 는 ddl-auto=create 가 같은 DDL 을 만들고, dev/qa/prd 는 ddl-auto=none·initialize-schema=never 라 이 파일을 외부에서 실행해야 한다. FK 는 저장소 관례대로 두지 않는다 (D7).

CREATE TABLE weekly_ranking_inputs (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  snapshot_id  VARCHAR(64)  NOT NULL,
  order_id     BIGINT       NOT NULL,
  product_id   BIGINT       NOT NULL,
  quantity     INT          NOT NULL,
  confirmed_at DATETIME(6)  NOT NULL,            -- UTC (D12)
  created_at   DATETIME(6)  NOT NULL,
  updated_at   DATETIME(6)  NOT NULL,
  deleted_at   DATETIME(6)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wri_snapshot_order_product (snapshot_id, order_id, product_id)
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_revisions (
  snapshot_id            VARCHAR(64)  NOT NULL,
  period_start           DATE         NOT NULL,   -- UTC 월요일
  revision               INT          NOT NULL,
  status                 VARCHAR(20)  NOT NULL,   -- CALCULATING | COMPLETED
  frozen_at              DATETIME(6)  NOT NULL,   -- Step 1 의 Clock.now (UTC)
  input_count            INT          NOT NULL,   -- 복사된 입력 행 수
  expected_product_count INT          NOT NULL,   -- distinct product_id 수
  input_checksum         CHAR(64)     NOT NULL,   -- D13
  result_checksum        CHAR(64)     NULL,       -- Step 3 에서 채움
  created_at             DATETIME(6)  NOT NULL,
  updated_at             DATETIME(6)  NOT NULL,
  PRIMARY KEY (snapshot_id),
  UNIQUE KEY uk_wrr_period_revision (period_start, revision)
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_scores (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  snapshot_id VARCHAR(64) NOT NULL,
  product_id  BIGINT      NOT NULL,
  score       BIGINT      NOT NULL,               -- 절대값 (quantity 합)
  ranking     INT         NULL,                   -- Step 3 이 한 TX 로 채움. `rank` 는 MySQL 8 예약어
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  deleted_at  DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wrs_snapshot_product (snapshot_id, product_id),   -- upsert 키
  KEY idx_wrs_snapshot_ranking (snapshot_id, ranking)             -- API 읽기용
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_publications (
  period_start DATE        NOT NULL,
  snapshot_id  VARCHAR(64) NOT NULL,
  revision     INT         NOT NULL,
  published_at DATETIME(6) NOT NULL,   -- Step 3 의 Clock.now (UTC)
  updated_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (period_start)
) ENGINE=InnoDB;
