-- 4주차 조회 성능 측정용 재현 seed (seed 프로필에서만 실행, docs/week4/design.md 참고)
-- Hibernate가 스키마를 만든 직후 실행된다(spring.jpa.defer-datasource-initialization).
-- 난수 없이 순번 공식으로만 만들어서 언제 실행해도 같은 데이터가 된다.
--
-- 검증값(실습 문서): 전체 상품 count = 10,000 / price 합계 = 472,685,000
--                    브랜드 7: count = 100 / price 합계 = 4,647,200

-- 1..10,000 순번 — 재귀 CTE는 기본 재귀 한도(1000)에 걸리므로 0~9 교차 조인으로 만든다.
CREATE TABLE seed_seq (n INT NOT NULL PRIMARY KEY);

INSERT INTO seed_seq (n)
SELECT d0.d + d1.d * 10 + d2.d * 100 + d3.d * 1000 + 1
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d0
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3;

-- 브랜드 100개. 브랜드 100은 3주차 일괄 삭제가 끝난 상태(브랜드·상품 모두 삭제)를 재현한다.
INSERT INTO brand (id, name, description, category, created_at, updated_at, deleted_at)
SELECT n,
       CONCAT('brand-', LPAD(n, 3, '0')),
       CONCAT('seed brand ', n),
       CONCAT('category-', n % 10),
       TIMESTAMP('2026-01-01 00:00:00'),
       TIMESTAMP('2026-01-01 00:00:00'),
       IF(n = 100, TIMESTAMP('2026-06-01 00:00:00'), NULL)
FROM seed_seq
WHERE n <= 100;

-- 사용자 500명 — 좋아요 관계용.
INSERT INTO user (id, point_balance, created_at, updated_at, deleted_at)
SELECT n, 1000000, TIMESTAMP('2026-01-01 00:00:00'), TIMESTAMP('2026-01-01 00:00:00'), NULL
FROM seed_seq
WHERE n <= 500;

-- 상품 10,000개 (상품 순번 i = id)
--   브랜드  = (i % 100) + 1
--   가격    = ((i * 37) % 100000) + 100   (37과 100000이 서로소라 1만 개 안에서 가격 중복 없음)
--   등록 시각 = 기준 시각 + i분            (latest 정렬이 순번과 같은 순서)
--   삭제    = 브랜드 100 전체 + i % 11 = 0 (브랜드와 무관하게 흩어진 약 9%)
INSERT INTO product (id, name, price, brand_id, stock_remaining, created_at, updated_at, deleted_at)
SELECT n,
       CONCAT('product-', LPAD(n, 5, '0')),
       ((n * 37) % 100000) + 100,
       (n % 100) + 1,
       100,
       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n MINUTE,
       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n MINUTE,
       IF((n % 100) + 1 = 100 OR n % 11 = 0, TIMESTAMP('2026-06-01 00:00:00'), NULL)
FROM seed_seq;

-- 좋아요: 상품 i에 사용자 1..f(i)가 좋아요
--   f(i) = 500        (i % 1000 = 0, 인기 상품 10개)
--        = 0          (i % 3 = 0, 좋아요 0개 상품 약 1/3)
--        = i % 50     (그 외 — 0~49 사이라 동점이 많다)
INSERT INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
SELECT u.n, p.n, TIMESTAMP('2026-02-01 00:00:00'), TIMESTAMP('2026-02-01 00:00:00'), NULL
FROM seed_seq p
JOIN seed_seq u
  ON u.n <= CASE
              WHEN p.n % 1000 = 0 THEN 500
              WHEN p.n % 3 = 0 THEN 0
              ELSE p.n % 50
            END;

DROP TABLE seed_seq;

ANALYZE TABLE brand, product, likes;
