-- =====================================================================
-- 상품 목록 조회(GET /api/v1/products) 성능 측정용 대량 데이터
--
-- 기본 규모: 브랜드 1천, 유저 100만, 상품 500만, 좋아요 약 800~1,000만
--
-- 전제
--   - 스키마가 이미 만들어져 있어야 한다 (local 프로필로 앱을 한 번 띄우면 ddl-auto: update 로 생성됨).
--   - local 프로필은 ddl-auto: update 라 앱을 다시 띄워도 데이터는 유지된다.
--     단 update 는 엔티티에서 뺀 인덱스를 DB 에서 지우지 않으므로, 인덱스를 빼고 비교할 때는 직접 DROP INDEX 한다.
--   - root 로 실행한다 (sql_log_bin, innodb_buffer_pool_size 변경에 권한이 필요).
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week4/seed.sql
--
-- 주의: brand / user / product / product_like / product_stat / catalog_revision 을 TRUNCATE 하고 시작한다.
--       revision 은 모두 0 으로 넣고 catalog_revision 행도 다시 만든다 (DR-37, docs/week5/cache-revision.sql).
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0. 규모·분포 파라미터
-- ---------------------------------------------------------------------
SET @brand_count    = 1000;
SET @user_count     = 1000000;   -- seq 크기(100만) 이하. 104729 의 배수면 안 된다 (5번 참고).
SET @product_count  = 5000000;
SET @like_scale     = 100000;    -- 가장 인기 있는 상품의 좋아요 수 근사치. @user_count 이하로 잘린다.
SET @like_skew      = 0.8;       -- Zipf 지수. 클수록 상위 상품에 더 쏠린다.
SET @brand_skew     = 3;         -- 클수록 앞번호 브랜드에 상품이 더 몰린다.
SET @deleted_ratio  = 0.05;      -- 소프트 삭제된 상품 비율
SET @span_seconds   = 94608000;  -- 상품 created_at 을 최근 3년에 고르게 깐다.

-- ---------------------------------------------------------------------
-- 1. 적재 속도 설정
-- ---------------------------------------------------------------------
SET GLOBAL innodb_buffer_pool_size = 1073741824;  -- 기본 128MB → 1GB
SET SESSION sql_log_bin = 0;
SET SESSION cte_max_recursion_depth = 1000000;

-- ---------------------------------------------------------------------
-- 2. 초기화
-- ---------------------------------------------------------------------
TRUNCATE TABLE catalog_revision;
TRUNCATE TABLE product_stat;
TRUNCATE TABLE product_like;
TRUNCATE TABLE product;
TRUNCATE TABLE brand;
TRUNCATE TABLE `user`;

-- 0 ~ 999,999 숫자 테이블
DROP TABLE IF EXISTS seq;
CREATE TABLE seq (n INT PRIMARY KEY);
INSERT INTO seq
WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM s WHERE n < 999999)
SELECT n FROM s;

SET @now  = UTC_TIMESTAMP(6);
SET @base = @now - INTERVAL @span_seconds SECOND;

-- ---------------------------------------------------------------------
-- 3. 브랜드 / 유저
-- ---------------------------------------------------------------------
INSERT INTO brand (name, revision, created_at, updated_at, deleted_at)
SELECT CONCAT('브랜드 ', n + 1), 0, @base, @base, NULL
FROM seq
WHERE n < @brand_count
ORDER BY n;

INSERT INTO `user` (is_admin, created_at, updated_at, deleted_at)
SELECT 0, @base, @base, NULL
FROM seq
WHERE n < @user_count
ORDER BY n;

-- ---------------------------------------------------------------------
-- 4. 상품 (100만 건씩 커밋)
--   - brand_id : 1 + FLOOR(B * RAND()^skew) → 앞번호 브랜드에 상품이 몰린다.
--   - price    : 10^(3~6) 를 100원 단위로 → 1천~100만 원, 싼 상품이 많다.
--   - created_at = updated_at : id 순으로 증가하도록 3년에 고르게 깐다.
--   - deleted_at : @deleted_ratio 만큼만 채운다.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS seed_products;
DELIMITER $$
CREATE PROCEDURE seed_products()
BEGIN
    DECLARE k INT DEFAULT 0;
    WHILE k * 1000000 < @product_count DO
        INSERT INTO product (brand_id, name, price, stock, revision, created_at, updated_at, deleted_at)
        SELECT 1 + FLOOR(@brand_count * POW(RAND(), @brand_skew)),
               CONCAT('상품 ', k * 1000000 + n + 1),
               ROUND(POW(10, 3 + RAND() * 3), -2),
               FLOOR(RAND() * 1000),
               0,
               @base + INTERVAL FLOOR((k * 1000000 + n) * @span_seconds / @product_count) SECOND,
               @base + INTERVAL FLOOR((k * 1000000 + n) * @span_seconds / @product_count) SECOND,
               IF(RAND() < @deleted_ratio, @now, NULL)
        FROM seq
        WHERE n < @product_count - k * 1000000
        ORDER BY n;
        SET k = k + 1;
    END WHILE;
END$$
DELIMITER ;

CALL seed_products();
DROP PROCEDURE seed_products;

-- ---------------------------------------------------------------------
-- 5. 좋아요
--   상품마다 좋아요 수를 Zipf 분포로 먼저 정한 뒤(product_pop), 그 수만큼 seq 와 조인해 늘린다.
--     like_count = FLOOR(scale / (1 + RAND() * P)^skew)
--     → 상위 몇 개는 수만~10만, 대부분은 0~1.
--   user_id = ((product_id * 7919 + n * 104729) mod U) + 1
--     → 같은 상품 안에서 n < U 이고 gcd(104729, U) = 1 이면 user_id 가 겹치지 않는다 (유니크 키 만족).
--   적재 동안 (user_id, product_id) 유니크 키를 떼어 뒀다가 마지막에 다시 붙인다
--   (user_id 가 무작위 순서로 들어와 B-tree 무작위 삽입이 되는 걸 피하려고).
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS product_pop;
CREATE TABLE product_pop (
    product_id BIGINT PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    like_count INT NOT NULL
);
INSERT INTO product_pop (product_id, created_at, like_count)
SELECT id,
       created_at,
       LEAST(FLOOR(@like_scale / POW(1 + RAND() * @product_count, @like_skew)), @user_count)
FROM product
ORDER BY id;

ALTER TABLE product_like DROP INDEX ux_product_like_user_product;

DROP PROCEDURE IF EXISTS seed_likes;
DELIMITER $$
CREATE PROCEDURE seed_likes()
BEGIN
    DECLARE lo BIGINT;
    DECLARE hi BIGINT;
    SELECT MIN(product_id), MAX(product_id) INTO lo, hi FROM product_pop;
    WHILE lo <= hi DO
        -- created_at: 상품 등록 이후 ~ 현재 사이 (created_at = updated_at 이 되도록 결정적으로 계산)
        INSERT INTO product_like (user_id, product_id, created_at, updated_at, deleted_at)
        SELECT ((p.product_id * 7919 + s.n * 104729) % @user_count) + 1,
               p.product_id,
               p.created_at + INTERVAL ((p.product_id * 31 + s.n * 7919) % GREATEST(TIMESTAMPDIFF(SECOND, p.created_at, @now), 1)) SECOND,
               p.created_at + INTERVAL ((p.product_id * 31 + s.n * 7919) % GREATEST(TIMESTAMPDIFF(SECOND, p.created_at, @now), 1)) SECOND,
               NULL
        FROM product_pop p
        JOIN seq s ON s.n < p.like_count
        WHERE p.like_count > 0
          AND p.product_id BETWEEN lo AND lo + 499999;
        SET lo = lo + 500000;
    END WHILE;
END$$
DELIMITER ;

CALL seed_likes();
DROP PROCEDURE seed_likes;

ALTER TABLE product_like ADD CONSTRAINT ux_product_like_user_product UNIQUE (user_id, product_id);

-- ---------------------------------------------------------------------
-- 5-1. 좋아요 수 (product_stat, DR-36)
--   상품마다 한 행. like_count 는 위에서 상품마다 넣은 좋아요 수(product_pop.like_count) 그대로다.
--   brand_id·deleted_at 은 상품 사본, created_at 은 상품 생성 시각.
--   적재 동안 보조 인덱스 2개를 떼어 두었다가 다시 붙인다 (backfill-product-stat.sql 과 같다).
-- ---------------------------------------------------------------------
ALTER TABLE product_stat
    DROP INDEX ix_product_stat_likes,
    DROP INDEX ix_product_stat_brand_likes;

INSERT INTO product_stat (product_id, brand_id, like_count, created_at, updated_at, deleted_at)
SELECT pr.id, pr.brand_id, pp.like_count, pr.created_at, @now, pr.deleted_at
FROM product pr
JOIN product_pop pp ON pp.product_id = pr.id
ORDER BY pr.id;

ALTER TABLE product_stat
    ADD INDEX ix_product_stat_likes (deleted_at, like_count, product_id),
    ADD INDEX ix_product_stat_brand_likes (brand_id, deleted_at, like_count, product_id);

-- 고객 상품 목록 캐시의 revision 행 (DR-37). 없으면 상품·브랜드 쓰기와 고객 목록 조회가 실패한다.
INSERT INTO catalog_revision (name, revision, created_at, updated_at, deleted_at)
VALUES ('product_list_latest', 0, @now, @now, NULL);

-- ---------------------------------------------------------------------
-- 6. 정리 / 통계 갱신
-- ---------------------------------------------------------------------
DROP TABLE product_pop;
DROP TABLE seq;

ANALYZE TABLE brand, `user`, product, product_like, product_stat;

-- ---------------------------------------------------------------------
-- 7. 확인
-- ---------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM brand)                                AS brands,
       (SELECT COUNT(*) FROM `user`)                               AS users,
       (SELECT COUNT(*) FROM product)                              AS products,
       (SELECT COUNT(*) FROM product WHERE deleted_at IS NOT NULL) AS deleted_products,
       (SELECT COUNT(*) FROM product_like)                         AS likes,
       (SELECT COUNT(*) FROM product_stat)                         AS stats,
       (SELECT SUM(like_count) FROM product_stat)                  AS like_sum;  -- stats = products, like_sum = likes

-- 좋아요 수 구간별 상품 수 (product_stat 기준)
SELECT CASE
           WHEN c = 0       THEN '0'
           WHEN c = 1       THEN '1'
           WHEN c < 10      THEN '2-9'
           WHEN c < 100     THEN '10-99'
           WHEN c < 1000    THEN '100-999'
           WHEN c < 10000   THEN '1000-9999'
           ELSE '10000+'
       END AS like_bucket,
       COUNT(*) AS products
FROM (
    SELECT product_id, like_count AS c
    FROM product_stat
) t
GROUP BY like_bucket
ORDER BY MIN(c);

-- 브랜드별 상품 수 상위 5
SELECT brand_id, COUNT(*) AS products
FROM product
GROUP BY brand_id
ORDER BY products DESC
LIMIT 5;
