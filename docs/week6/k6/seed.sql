-- 6주차 실험 데이터. W5 seed(사용자 1,000 · 상품 10 · 재고 각 100만) 뒤에 W6 테이블을 비운다.
-- 실행: cat docs/week5/k6/seed.sql docs/week6/k6/seed.sql | docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6
TRUNCATE TABLE event_outbox;
TRUNCATE TABLE consumed_order_confirmation;
TRUNCATE TABLE product_purchase_activity;
