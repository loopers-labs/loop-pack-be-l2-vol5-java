-- 테스트 전용 재시작 probe 확인 테이블(Implementation Quest 3절).
CREATE TABLE batch_restart_probe (
  snapshot_id VARCHAR(64) NOT NULL,
  product_id BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, product_id)
) ENGINE=InnoDB;
