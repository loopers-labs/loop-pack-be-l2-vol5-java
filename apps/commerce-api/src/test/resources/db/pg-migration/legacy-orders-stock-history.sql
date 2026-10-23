-- 전환 재현용 과거 스키마(테스트 fixture). PG 전환 전 커밋(e1c5c25)의 OrderModel·StockHistoryModel 매핑을
-- Hibernate 6.6 MySQL create 가 만드는 형식으로 적은 것이며, 설계 3.6 사전 확인의 기대 정의와 같다.
CREATE TABLE orders (
  created_at datetime(6) NOT NULL,
  deleted_at datetime(6) DEFAULT NULL,
  id bigint NOT NULL AUTO_INCREMENT,
  order_total bigint NOT NULL,
  payment_amount bigint DEFAULT NULL,
  updated_at datetime(6) NOT NULL,
  used_point_amount bigint DEFAULT NULL,
  user_id bigint NOT NULL,
  status enum('CONFIRMED','DRAFT') NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE stock_history (
  after_quantity bigint NOT NULL,
  before_quantity bigint NOT NULL,
  changed_quantity bigint NOT NULL,
  created_at datetime(6) NOT NULL,
  deleted_at datetime(6) DEFAULT NULL,
  id bigint NOT NULL AUTO_INCREMENT,
  order_id bigint DEFAULT NULL,
  product_id bigint NOT NULL,
  updated_at datetime(6) NOT NULL,
  cause enum('ADMIN_CHANGE','ORDER_DEDUCTION') NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;
