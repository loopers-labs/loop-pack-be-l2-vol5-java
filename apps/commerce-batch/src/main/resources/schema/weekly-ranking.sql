-- 주간 구매 랭킹 테이블. commerce-batch가 소유하고, commerce-api는 ranking_entry·ranking_publication을 읽기만 한다.
-- 시각 열은 UTC 값이다. 이미 있으면 그대로 두어 실행·재시작 사이에 데이터가 남는다(바뀐 정의는 반영되지 않는다).
-- ranking_snapshot.status: FROZEN(입력 고정) → COMPLETED(완성 랭킹 저장). 운영자가 FROZEN을 ABANDONED(결과를 쓰지 않음)로 바꿀 수 있다.
-- 공개 여부는 ranking_publication만 정한다.

CREATE TABLE IF NOT EXISTS ranking_snapshot (
  snapshot_id VARCHAR(64) NOT NULL,
  period_start DATE NOT NULL,
  revision INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  frozen_at DATETIME(6) NOT NULL,
  input_count INT NOT NULL,
  product_count INT NOT NULL,
  input_checksum CHAR(64) NOT NULL,
  result_checksum CHAR(64) NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (snapshot_id),
  UNIQUE KEY uk_ranking_snapshot_period_revision (period_start, revision)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_snapshot_input (
  snapshot_id VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  confirmed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (snapshot_id, order_id, product_id),
  KEY idx_ranking_snapshot_input_product (snapshot_id, product_id, quantity)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_score_stage (
  snapshot_id VARCHAR(64) NOT NULL,
  product_id BIGINT NOT NULL,
  score BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, product_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_entry (
  snapshot_id VARCHAR(64) NOT NULL,
  product_id BIGINT NOT NULL,
  score BIGINT NOT NULL,
  ranking INT NOT NULL,
  PRIMARY KEY (snapshot_id, product_id),
  UNIQUE KEY uk_ranking_entry_rank (snapshot_id, ranking)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_publication (
  period_start DATE NOT NULL,
  snapshot_id VARCHAR(64) NOT NULL,
  revision INT NOT NULL,
  published_at DATETIME(6) NOT NULL,
  PRIMARY KEY (period_start)
) ENGINE=InnoDB;
