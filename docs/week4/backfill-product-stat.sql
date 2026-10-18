-- =====================================================================
-- product_stat 백필 (MySQL, DR-36)
--
-- 이미 적재된 product / product_like 로 상품마다 좋아요 수 행을 하나씩 만든다.
-- 데이터를 다시 만들지 않고 그대로 두어, 같은 데이터에서 변경 전후를 비교한다.
--
-- 전제
--   - product_stat 테이블이 있어야 한다 (local 프로필로 앱을 한 번 띄우면 ddl-auto: update 로 생성됨).
--   - ix_product_like_product 가 아직 있어야 빠르다 (구간별 좋아요 집계가 이 인덱스를 커버링으로 읽는다).
--     지우는 건 이 스크립트 뒤에 따로 한다.
--   - root 로 실행한다 (sql_log_bin 변경에 권한이 필요).
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week4/backfill-product-stat.sql
--
-- 주의: product_stat 을 TRUNCATE 하고 시작한다. 다시 돌려도 결과가 같다.
-- =====================================================================

SET NAMES utf8mb4;
SET SESSION sql_log_bin = 0;

TRUNCATE TABLE product_stat;

-- 적재 동안 보조 인덱스 2개를 떼어 두었다가 마지막에 다시 붙인다.
-- ux_product_stat_product 는 product_id 순서로 넣으면 순차 삽입이라 남겨 둔다.
ALTER TABLE product_stat
    DROP INDEX ix_product_stat_likes,
    DROP INDEX ix_product_stat_brand_likes;

-- 상품 id 50만 개씩 끊어 넣는다. 한 트랜잭션에 500만 행을 넣지 않으려고.
--   brand_id·deleted_at: 상품 사본 / created_at: 상품 생성 시각 (상품과 함께 만들어지는 행) / updated_at: 지금
DROP PROCEDURE IF EXISTS backfill_product_stat;
DELIMITER $$
CREATE PROCEDURE backfill_product_stat()
BEGIN
    DECLARE lo BIGINT;
    DECLARE hi BIGINT;
    DECLARE now6 DATETIME(6) DEFAULT NOW(6);
    SELECT MIN(id), MAX(id) INTO lo, hi FROM product;
    WHILE lo <= hi DO
        INSERT INTO product_stat (product_id, brand_id, like_count, created_at, updated_at, deleted_at)
        SELECT p.id, p.brand_id, COALESCE(l.cnt, 0), p.created_at, now6, p.deleted_at
        FROM product p
        LEFT JOIN (
            SELECT product_id, COUNT(*) AS cnt
            FROM product_like
            WHERE product_id BETWEEN lo AND lo + 499999
            GROUP BY product_id
        ) l ON l.product_id = p.id
        WHERE p.id BETWEEN lo AND lo + 499999
        ORDER BY p.id;
        SET lo = lo + 500000;
    END WHILE;
END$$
DELIMITER ;

CALL backfill_product_stat();
DROP PROCEDURE backfill_product_stat;

-- 엔티티(ProductStatModel)와 같은 이름·컬럼 순서
ALTER TABLE product_stat
    ADD INDEX ix_product_stat_likes (deleted_at, like_count, product_id),
    ADD INDEX ix_product_stat_brand_likes (brand_id, deleted_at, like_count, product_id);

ANALYZE TABLE product_stat;

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
