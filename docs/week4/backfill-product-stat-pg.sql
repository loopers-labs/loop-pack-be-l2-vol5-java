-- =====================================================================
-- product_stat 백필 (PostgreSQL, DR-36)
--
-- backfill-product-stat.sql (MySQL) 과 같은 일을 PostgreSQL 에서 한다.
-- 이미 적재된 product / product_like 로 상품마다 좋아요 수 행을 하나씩 만든다.
--
-- 전제
--   - product_stat 테이블이 있어야 한다 (local-pg 프로필로 앱을 한 번 띄우면 ddl-auto: update 로 생성됨).
--   - VACUUM 은 트랜잭션 안에서 돌 수 없으므로 자동 커밋 모드로 실행한다 (psql 에 -1 / --single-transaction 을 주지 않는다).
--
-- 실행
--   docker exec -i docker-postgres-1 psql -U application -d loopers -v ON_ERROR_STOP=1 < docs/week4/backfill-product-stat-pg.sql
--
-- 주의: product_stat 을 TRUNCATE 하고 시작한다. 다시 돌려도 결과가 같다.
-- =====================================================================

-- 좋아요 760만 건 집계가 디스크로 넘치지 않게 이 세션에서만 올린다 (서버 기본 4MB).
SET work_mem = '256MB';

TRUNCATE TABLE product_stat RESTART IDENTITY;

-- 적재 동안 보조 인덱스 2개를 떼어 두었다가 마지막에 다시 붙인다.
-- ux_product_stat_product 는 product_id 순서로 넣으면 순차 삽입이라 남겨 둔다.
DROP INDEX IF EXISTS ix_product_stat_likes;
DROP INDEX IF EXISTS ix_product_stat_brand_likes;

-- 한 문장으로 넣는다 (MySQL 처럼 undo 가 커지는 부담이 없다).
--   brand_id·deleted_at: 상품 사본 / created_at: 상품 생성 시각 (상품과 함께 만들어지는 행) / updated_at: 지금
INSERT INTO product_stat (product_id, brand_id, like_count, created_at, updated_at, deleted_at)
SELECT p.id, p.brand_id, COALESCE(l.cnt, 0), p.created_at, now(), p.deleted_at
FROM product p
LEFT JOIN (
    SELECT product_id, COUNT(*) AS cnt
    FROM product_like
    GROUP BY product_id
) l ON l.product_id = p.id
ORDER BY p.id;

-- 엔티티(ProductStatModel)와 같은 이름·컬럼 순서
CREATE INDEX ix_product_stat_likes ON product_stat (deleted_at, like_count, product_id);
CREATE INDEX ix_product_stat_brand_likes ON product_stat (brand_id, deleted_at, like_count, product_id);

-- 대량 삽입 직후에는 visibility map 이 비어 Index Only Scan 도 테이블을 다시 읽는다 (Heap Fetches).
-- 측정 전에 VACUUM 으로 채우고 통계도 갱신한다.
VACUUM ANALYZE product_stat;

RESET work_mem;

-- ---------------------------------------------------------------------
-- 검증. 아래 값이 모두 맞아야 한다.
--   stats = products, like_sum = likes, count_mismatch = 0, copy_mismatch = 0
-- ---------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM product)                AS products,
       (SELECT COUNT(*) FROM product_stat)           AS stats,
       (SELECT COUNT(*) FROM product_like)           AS likes,
       (SELECT SUM(like_count) FROM product_stat)    AS like_sum;

SELECT COUNT(*) AS count_mismatch
FROM product_stat s
LEFT JOIN (
    SELECT product_id, COUNT(*) AS cnt FROM product_like GROUP BY product_id
) l ON l.product_id = s.product_id
WHERE s.like_count <> COALESCE(l.cnt, 0);

SELECT COUNT(*) AS copy_mismatch
FROM product p
JOIN product_stat s ON s.product_id = p.id
WHERE (p.deleted_at IS NULL) <> (s.deleted_at IS NULL)
   OR p.brand_id <> s.brand_id;
