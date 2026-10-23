-- 5주차 k6 실험 데이터. 실험마다 앞에서 한 번 돌려 같은 출발점에서 시작한다 (주문·결제·예약을 비우고 재고를 되돌린다).
-- 실행: docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_k6 < docs/week5/k6/seed.sql
--
-- 사용자 1,000명: k6 가상 사용자가 X-USER-ID 1~1000 을 돌려 쓴다.
-- 상품 10개 · 재고 각 1,000,000: 100 TPS × 90초 = 9,000건이 재고 부족으로 거절되지 않게 넉넉히 둔다.
--   재고 부족이 섞이면 "PG 장애로 실패"와 구분되지 않는다.

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE payment_outbox;
TRUNCATE TABLE payments;
TRUNCATE TABLE stock_reservations;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE point_histories;
TRUNCATE TABLE point_groups;
TRUNCATE TABLE likes;
TRUNCATE TABLE products;
TRUNCATE TABLE brands;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

SET @seeded_at = '2026-01-01 00:00:00';

INSERT INTO brands (id, name, description, created_at, updated_at)
VALUES (1, 'k6 브랜드', '5주차 결제 부하 실험', @seeded_at, @seeded_at);

INSERT INTO products (id, brand_id, name, price, stock, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10)
SELECT n, 1, CONCAT('k6 상품 ', LPAD(n, 2, '0')), n * 1000, 1000000, @seeded_at, @seeded_at FROM seq;

INSERT INTO users (id, name, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, CONCAT('k6 사용자 ', LPAD(n, 4, '0')), @seeded_at, @seeded_at FROM seq;

SELECT (SELECT COUNT(*) FROM users) AS users, (SELECT COUNT(*) FROM products) AS products,
       (SELECT SUM(stock) FROM products) AS total_stock, (SELECT COUNT(*) FROM orders) AS orders;
