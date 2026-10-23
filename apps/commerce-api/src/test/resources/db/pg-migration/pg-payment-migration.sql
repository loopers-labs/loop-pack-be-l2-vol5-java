-- PG 결제 전환의 기존 데이터 보존 DDL(설계 3.6 전환 순서 3~5). 사전 확인(1·2)을 통과한 DB에서만 한 번 실행한다.
-- 자동 재실행·부분 복구를 하지 않는다. 사후 확인(6) 뒤 새 코드는 ddl-auto=validate 로 기동한다.

-- 3) 기존 ENUM 허용값 확장: 기존 값·순서 유지, 새 값만 뒤에 추가
ALTER TABLE orders
  MODIFY COLUMN status ENUM('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING') NOT NULL;
ALTER TABLE stock_history
  MODIFY COLUMN cause ENUM('ADMIN_CHANGE','ORDER_DEDUCTION','ORDER_RELEASE','ORDER_RESERVATION') NOT NULL;

-- 4) 금액 컬럼 이름 전환·결제 방식 열 추가, 과거 POINT 보정
ALTER TABLE orders
  RENAME COLUMN order_total TO total_amount,
  RENAME COLUMN used_point_amount TO point_amount,
  RENAME COLUMN payment_amount TO paid_amount,
  ADD COLUMN payment_method VARCHAR(16) NULL;
UPDATE orders
SET payment_method = 'POINT'
WHERE status = 'CONFIRMED' AND payment_method IS NULL;

-- 5) 신규 테이블: 키 열은 ASCII binary, 신규 enum 열은 값 CHECK 없는 VARCHAR, 물리 FK 없음
CREATE TABLE payment (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  deleted_at datetime(6) DEFAULT NULL,
  order_id bigint NOT NULL,
  attempt_key char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_account varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_order_id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transaction_key varchar(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  amount bigint NOT NULL,
  status varchar(16) NOT NULL,
  failure_kind varchar(32) DEFAULT NULL,
  dispatch_claimed_at datetime(6) NOT NULL,
  verification_status varchar(16) NOT NULL,
  verification_checked_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_order (order_id),
  UNIQUE KEY uk_payment_key (attempt_key),
  UNIQUE KEY uk_payment_transaction (provider, provider_account, transaction_key),
  KEY idx_payment_provider_order (provider, provider_account, provider_order_id),
  CONSTRAINT ck_payment_amount CHECK (amount > 0)
) ENGINE=InnoDB;

CREATE TABLE payment_conflict (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  deleted_at datetime(6) DEFAULT NULL,
  payment_id bigint NOT NULL,
  code varchar(48) NOT NULL,
  observed_order_id text,
  observed_transaction_key text,
  observed_amount bigint DEFAULT NULL,
  observed_status varchar(16) DEFAULT NULL,
  observed_count int DEFAULT NULL,
  detected_at datetime(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_conflict_payment (payment_id)
) ENGINE=InnoDB;
