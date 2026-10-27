-- 주문 확정 이벤트를 켜기 전에 애플리케이션 쓰기를 멈추고 기존 DB에 한 번 적용한다. 새 DB는 init.sql을 사용한다.
-- 이미 확정한 주문의 확정 시각은 NULL로 두고, 다른 시각으로 추정해 채우지 않는다.
ALTER TABLE loopers.orders ADD COLUMN confirmed_at DATETIME(6) DEFAULT NULL;
