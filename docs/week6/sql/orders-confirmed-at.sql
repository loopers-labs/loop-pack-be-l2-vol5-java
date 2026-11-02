-- W5 주문 DB에 한 번 적용한다. 기존 POINT/확정 이력의 시각을 추측해 채우지 않는다.
ALTER TABLE orders ADD COLUMN confirmed_at DATETIME(6) NULL;
