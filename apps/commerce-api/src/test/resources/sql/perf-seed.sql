-- 4주차 상품 목록 조회 측정용 seed. 같은 입력이면 항상 같은 자료가 만들어진다(난수 없음).
-- 성능 측정에서만 쓴다. local 프로필 앱이 ddl-auto: create 로 만든 비어 있는 테이블(brands, products, users, likes, product_like_counts)에 적재한다.
-- 앱을 다시 띄우면 지워지므로 필요할 때 다시 적재한다.
-- 임시 테이블 perf_seq 만 만들었다 지우며, 다른 테이블의 데이터는 건드리지 않는다.
--
-- 구성
--   브랜드  : id 1..100
--   사용자  : id 1..1000
--   상품    : 노출 상품 id 1..10000 (i = id)
--               브랜드 = (i % 100) + 1, 가격 = ((i * 37) % 100000) + 100, 재고 100
--               삭제 상품 id 10001..10200 (200개). 같은 공식으로 만들고 deleted_at 만 채운다.
--               노출 상품의 통계는 삭제 상품과 무관하다(검산 값 참고).
--   좋아요  : 상품 i 의 좋아요 수 n(i), 사용자 = ((i * 13 + k * 7) % 1000) + 1 (k = 0..n-1, 7 과 1000 은 서로소라 중복 없음)
--               i % 11 = 0                     -> 0개           (상품의 약 9%. 11 과 브랜드 주기 100 이 서로소라 모든 브랜드에 고르게 퍼짐)
--               i % 97 = 0 (i % 11 != 0)        -> 100 + (i % 5) * 50 (인기 상품. 100~300개)
--               그 외                           -> 1 + (i % 7)   (1~7개. 같은 값이 많이 반복되어 동점이 생김)
--             삭제 상품에도 같은 규칙으로 좋아요를 넣는다(삭제 상품이 집계·정렬에 섞이지 않는지 보려는 것).
--
-- 검산 값(노출 상품 기준): 전체 count = 10,000 / price 합 = 472,685,000 / 브랜드 7: count = 100, price 합 = 4,647,200
-- 가격은 i * 37 mod 100000 이 서로 달라 가격 동점이 없다. 동점은 별도의 작은 자료에서 확인한다.

SET SESSION cte_max_recursion_depth = 20000;

DROP TABLE IF EXISTS perf_seq;
CREATE TABLE perf_seq (n INT NOT NULL PRIMARY KEY);
INSERT INTO perf_seq (n)
WITH RECURSIVE s (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM s WHERE n < 10200)
SELECT n FROM s;

INSERT INTO brands (id, name, created_at, updated_at, deleted_at)
SELECT n, CONCAT('brand-', n), '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000', NULL
FROM perf_seq WHERE n BETWEEN 1 AND 100;

INSERT INTO users (id, point_balance, created_at, updated_at, deleted_at)
SELECT n, 0, '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000', NULL
FROM perf_seq WHERE n BETWEEN 1 AND 1000;

INSERT INTO products (id, brand_id, name, price, stock, created_at, updated_at, deleted_at)
SELECT n,
       (n % 100) + 1,
       CONCAT('product-', n),
       ((n * 37) % 100000) + 100,
       100,
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00.000000'),
       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00.000000'),
       CASE WHEN n > 10000 THEN '2026-06-01 00:00:00.000000' ELSE NULL END
FROM perf_seq WHERE n BETWEEN 1 AND 10200;

INSERT INTO likes (product_id, user_id, liked_at)
SELECT p.n,
       ((p.n * 13 + k.n * 7) % 1000) + 1,
       TIMESTAMPADD(SECOND, (p.n * 31 + k.n) % 100000, '2026-02-01 00:00:00.000000')
FROM perf_seq p
JOIN perf_seq k
  ON k.n < CASE
             WHEN p.n % 11 = 0 THEN 0
             WHEN p.n % 97 = 0 THEN 100 + (p.n % 5) * 50
             ELSE 1 + (p.n % 7)
           END
WHERE p.n BETWEEN 1 AND 10200;

-- 상품별 좋아요 수 집계(노출 상품만 행을 가진다). 앱이 같은 규칙으로 유지한다.
INSERT INTO product_like_counts (product_id, like_count)
SELECT p.id, (SELECT COUNT(*) FROM likes l WHERE l.product_id = p.id)
FROM products p
WHERE p.deleted_at IS NULL;

DROP TABLE perf_seq;
ANALYZE TABLE brands, products, users, likes, product_like_counts;
