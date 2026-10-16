-- 상품 조회 성능 측정용 seed (로컬 docker MySQL 수동 실행, 테스트 재사용용. 앱이 자동 실행하는 파일 아님)
--
-- 전제: 앱을 local 프로필로 기동해 테이블이 만들어진 상태 (ddl-auto: create)
-- 실행: docker compose -f ./docker/infra-compose.yml exec -T mysql \
--         mysql -uapplication -papplication loopers < apps/commerce-api/src/test/resources/sql/product-perf-seed.sql
-- 검증: product-perf-verify.sql
--
-- 랜덤 없이 아래 공식으로만 생성하므로 다시 실행해도 같은 결과가 나온다.
--   brands   : id 1~100,  name 'brand-{n}'
--   users    : id 1~1000
--   products : id i = 1~10000
--              brand_id   = (i % 100) + 1
--              amount     = ((i * 37) % 100000) + 100
--              quantity   = 100
--              name       = 'product-{i}'
--              created_at = updated_at = '2026-01-01 00:00:00' + ((i * 7919) % 10000) 분
--   likes    : 상품 i 마다 user_id 1 부터 k 개 (조건은 위에서부터 순서대로 적용)
--              k = 500                 (i % 999 = 0)
--                = 0                   (i % 11 = 0)
--                = ((i * 13) % 47) + 1 (그 외)
--   deleted_at 은 전부 NULL (좋아요 취소는 물리 삭제라 채워진 행을 만들지 않는다)

-- MySQL 8 기본 재귀 깊이(1000)로는 10000 까지 못 가므로 세션 한정으로 올린다.
SET SESSION cte_max_recursion_depth = 10001;

-- 네 테이블 사이에는 외래키가 없어 FOREIGN_KEY_CHECKS 조작 없이 비운다.
TRUNCATE TABLE likes;
TRUNCATE TABLE products;
TRUNCATE TABLE users;
TRUNCATE TABLE brands;

-- brands: 1~100
INSERT INTO brands (id, name, created_at, updated_at, deleted_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
SELECT
    n,
    CONCAT('brand-', n),
    TIMESTAMP '2026-01-01 00:00:00',
    TIMESTAMP '2026-01-01 00:00:00',
    NULL
FROM seq;

-- users: 1~1000
INSERT INTO users (id, created_at, updated_at, deleted_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT
    n,
    TIMESTAMP '2026-01-01 00:00:00',
    TIMESTAMP '2026-01-01 00:00:00',
    NULL
FROM seq;

-- products: 1~10000
INSERT INTO products (id, brand_id, name, amount, quantity, created_at, updated_at, deleted_at)
WITH RECURSIVE seq(i) AS (
    SELECT 1
    UNION ALL
    SELECT i + 1 FROM seq WHERE i < 10000
)
SELECT
    i,
    (i % 100) + 1,
    CONCAT('product-', i),
    ((i * 37) % 100000) + 100,
    100,
    TIMESTAMP '2026-01-01 00:00:00' + INTERVAL ((i * 7919) % 10000) MINUTE,
    TIMESTAMP '2026-01-01 00:00:00' + INTERVAL ((i * 7919) % 10000) MINUTE,
    NULL
FROM seq;

-- likes: 상품 i × 사용자 1~500 을 교차한 뒤 user_n <= k(i) 인 조합만 넣는다.
-- 한 상품에 user_id 가 1..k 로 한 번씩만 들어가므로 uk_likes_user_product 를 지킨다.
-- ORDER BY 로 삽입 순서를 고정해 AUTO_INCREMENT id 도 매번 같게 만든다.
INSERT INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE product_seq(i) AS (
    SELECT 1
    UNION ALL
    SELECT i + 1 FROM product_seq WHERE i < 10000
),
user_seq(u) AS (
    SELECT 1
    UNION ALL
    SELECT u + 1 FROM user_seq WHERE u < 500
),
like_count AS (
    SELECT
        i,
        CASE
            WHEN i % 999 = 0 THEN 500
            WHEN i % 11 = 0 THEN 0
            ELSE ((i * 13) % 47) + 1
        END AS k
    FROM product_seq
)
SELECT
    u,
    i,
    TIMESTAMP '2026-01-01 00:00:00',
    TIMESTAMP '2026-01-01 00:00:00',
    NULL
FROM like_count
JOIN user_seq ON u <= k
ORDER BY i, u;

ANALYZE TABLE brands, users, products, likes;
