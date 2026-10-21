-- =====================================================================
-- 상품 목록 조회(GET /api/v1/products) 성능 측정용 대량 데이터 — PostgreSQL 판
-- seed.sql(MySQL)과 같은 규모·분포를 PostgreSQL 문법으로 옮긴 것이다.
--
-- 기본 규모: 브랜드 1천, 유저 100만, 상품 500만, 좋아요 약 750~800만
--
-- 전제
--   - 스키마가 이미 만들어져 있어야 한다 (local-pg 프로필로 앱을 한 번 띄우면 ddl-auto: update 로 생성됨).
--   - local-pg 프로필은 ddl-auto: update 라 앱을 다시 띄워도 데이터는 유지된다.
--     단 update 는 엔티티에서 뺀 인덱스를 DB 에서 지우지 않으므로, 인덱스를 빼고 비교할 때는 직접 DROP INDEX 한다.
--   - product.stock 컬럼이 있어야 한다. 엔티티에서 이 컬럼을 뺐으므로(DR-38) 새로 만든 스키마에는 없다. 그때는 먼저
--       ALTER TABLE product ADD COLUMN stock integer NOT NULL DEFAULT 0;
--     로 만들고, seed 뒤에 docs/week5/product-stock-pg.sql 로 재고 테이블에 옮기고 컬럼을 지운다.
--   - DO 블록 안에서 COMMIT 하므로 자동 커밋 모드로 실행한다 (psql 에 -1 / --single-transaction 을 주지 않는다).
--
-- 실행
--   docker exec -i docker-postgres-1 psql -U application -d loopers -v ON_ERROR_STOP=1 < docs/week4/seed-pg.sql
--
-- 주의: brand / user / product / product_like / product_stat / catalog_revision 을 TRUNCATE 하고 시작한다 (id 도 1부터 다시 매긴다).
--       revision 은 모두 0 으로 넣고 catalog_revision 행도 다시 만든다 (DR-37, docs/week4/cache-revision-pg.sql).
-- =====================================================================

SET client_encoding = 'UTF8';

-- ---------------------------------------------------------------------
-- 0. 규모·분포 파라미터 (세션 임시 테이블, DO 블록 안에서도 읽는다)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS seed_param;
CREATE TEMP TABLE seed_param AS
SELECT 1000::bigint     AS brand_count,
       1000000::bigint  AS user_count,     -- 104729 의 배수면 안 된다 (5번 참고).
       5000000::bigint  AS product_count,
       100000::bigint   AS like_scale,     -- 가장 인기 있는 상품의 좋아요 수 근사치. user_count 이하로 잘린다.
       0.8::float8      AS like_skew,      -- Zipf 지수. 클수록 상위 상품에 더 쏠린다.
       3::float8        AS brand_skew,     -- 클수록 앞번호 브랜드에 상품이 더 몰린다.
       0.05::float8     AS deleted_ratio,  -- 소프트 삭제된 상품 비율
       94608000::bigint AS span_seconds;   -- 상품 created_at 을 최근 3년에 고르게 깐다.

-- ---------------------------------------------------------------------
-- 1. 적재 속도 설정 (세션 한정, 권한 불필요)
--   MySQL 판의 innodb_buffer_pool_size 는 서버 재시작이 필요한 shared_buffers 에 해당해 여기서는 바꾸지 않는다.
-- ---------------------------------------------------------------------
SET synchronous_commit = off;
SET maintenance_work_mem = '512MB';  -- 유니크 제약 재생성(인덱스 빌드)용
SET work_mem = '256MB';

-- ---------------------------------------------------------------------
-- 2. 초기화
-- ---------------------------------------------------------------------
TRUNCATE TABLE catalog_revision, product_stat, product_like, product, brand, "user" RESTART IDENTITY;

ALTER TABLE seed_param ADD COLUMN now timestamptz, ADD COLUMN base timestamptz;
UPDATE seed_param
SET now  = clock_timestamp(),
    base = clock_timestamp() - span_seconds * interval '1 second';

-- ---------------------------------------------------------------------
-- 3. 브랜드 / 유저
-- ---------------------------------------------------------------------
INSERT INTO brand (name, revision, created_at, updated_at, deleted_at)
SELECT '브랜드 ' || (n + 1), 0, p.base, p.base, NULL
FROM seed_param p, generate_series(0, p.brand_count - 1) AS n
ORDER BY n;

INSERT INTO "user" (is_admin, created_at, updated_at, deleted_at)
SELECT false, p.base, p.base, NULL
FROM seed_param p, generate_series(0, p.user_count - 1) AS n
ORDER BY n;

-- ---------------------------------------------------------------------
-- 4. 상품 (100만 건씩 커밋)
--   - brand_id : 1 + FLOOR(B * random()^skew) → 앞번호 브랜드에 상품이 몰린다.
--   - price    : 10^(3~6) 를 100원 단위로 → 1천~100만 원, 싼 상품이 많다.
--   - created_at = updated_at : id 순으로 증가하도록 3년에 고르게 깐다.
--   - deleted_at : deleted_ratio 만큼만 채운다.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    k bigint := 0;
    total bigint;
BEGIN
    SELECT product_count INTO total FROM seed_param;
    WHILE k * 1000000 < total LOOP
        INSERT INTO product (brand_id, name, price, stock, revision, created_at, updated_at, deleted_at)
        SELECT 1 + floor(p.brand_count * power(random(), p.brand_skew)),
               '상품 ' || (k * 1000000 + n + 1),
               round(power(10, 3 + random() * 3)::numeric, -2),
               floor(random() * 1000),
               0,
               p.base + ((k * 1000000 + n) * p.span_seconds / p.product_count) * interval '1 second',
               p.base + ((k * 1000000 + n) * p.span_seconds / p.product_count) * interval '1 second',
               CASE WHEN random() < p.deleted_ratio THEN p.now END
        FROM seed_param p, generate_series(0, least(1000000, p.product_count - k * 1000000) - 1) AS n
        ORDER BY n;
        COMMIT;
        k := k + 1;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 5. 좋아요
--   상품마다 좋아요 수를 Zipf 분포로 먼저 정한 뒤(product_pop), 그 수만큼 generate_series 로 늘린다.
--     like_count = FLOOR(scale / (1 + random() * P)^skew)
--     → 상위 몇 개는 수만~10만, 대부분은 0~1.
--   user_id = ((product_id * 7919 + n * 104729) mod U) + 1
--     → 같은 상품 안에서 n < U 이고 gcd(104729, U) = 1 이면 user_id 가 겹치지 않는다 (유니크 키 만족).
--   적재 동안 (user_id, product_id) 유니크 제약을 떼어 뒀다가 마지막에 다시 붙인다
--   (user_id 가 무작위 순서로 들어와 B-tree 무작위 삽입이 되는 걸 피하려고).
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS product_pop;
CREATE TEMP TABLE product_pop (
    product_id bigint PRIMARY KEY,
    created_at timestamptz NOT NULL,
    like_count bigint NOT NULL
);
INSERT INTO product_pop (product_id, created_at, like_count)
SELECT pr.id,
       pr.created_at,
       least(floor(p.like_scale / power(1 + random() * p.product_count, p.like_skew)), p.user_count)
FROM seed_param p, product pr
ORDER BY pr.id;

ALTER TABLE product_like DROP CONSTRAINT ux_product_like_user_product;

DO $$
DECLARE
    lo bigint;
    hi bigint;
BEGIN
    SELECT min(product_id), max(product_id) INTO lo, hi FROM product_pop;
    WHILE lo <= hi LOOP
        -- created_at: 상품 등록 이후 ~ 현재 사이 (created_at = updated_at 이 되도록 결정적으로 계산)
        INSERT INTO product_like (user_id, product_id, created_at, updated_at, deleted_at)
        SELECT ((pp.product_id * 7919 + s.n * 104729) % p.user_count) + 1,
               pp.product_id,
               pp.created_at + ((pp.product_id * 31 + s.n * 7919)
                   % greatest(floor(extract(epoch FROM p.now - pp.created_at))::bigint, 1)) * interval '1 second',
               pp.created_at + ((pp.product_id * 31 + s.n * 7919)
                   % greatest(floor(extract(epoch FROM p.now - pp.created_at))::bigint, 1)) * interval '1 second',
               NULL
        FROM seed_param p, product_pop pp
        CROSS JOIN LATERAL generate_series(0::bigint, pp.like_count - 1) AS s(n)
        WHERE pp.like_count > 0
          AND pp.product_id BETWEEN lo AND lo + 499999;
        COMMIT;
        lo := lo + 500000;
    END LOOP;
END $$;

ALTER TABLE product_like ADD CONSTRAINT ux_product_like_user_product UNIQUE (user_id, product_id);

-- ---------------------------------------------------------------------
-- 5-1. 좋아요 수 (product_stat, DR-36)
--   상품마다 한 행. like_count 는 위에서 상품마다 넣은 좋아요 수(product_pop.like_count) 그대로다.
--   brand_id·deleted_at 은 상품 사본, created_at 은 상품 생성 시각.
--   적재 동안 보조 인덱스 2개를 떼어 두었다가 다시 붙인다 (backfill-product-stat-pg.sql 과 같다).
-- ---------------------------------------------------------------------
DROP INDEX IF EXISTS ix_product_stat_likes;
DROP INDEX IF EXISTS ix_product_stat_brand_likes;

INSERT INTO product_stat (product_id, brand_id, like_count, created_at, updated_at, deleted_at)
SELECT pr.id, pr.brand_id, pp.like_count, pr.created_at, p.now, pr.deleted_at
FROM seed_param p, product pr
JOIN product_pop pp ON pp.product_id = pr.id
ORDER BY pr.id;

CREATE INDEX ix_product_stat_likes ON product_stat (deleted_at, like_count, product_id);
CREATE INDEX ix_product_stat_brand_likes ON product_stat (brand_id, deleted_at, like_count, product_id);

-- 고객 상품 목록 캐시의 revision 행 (DR-37). 없으면 상품·브랜드 쓰기와 고객 목록 조회가 실패한다.
INSERT INTO catalog_revision (name, revision, created_at, updated_at, deleted_at)
SELECT 'product_list_latest', 0, p.now, p.now, NULL
FROM seed_param p;

-- ---------------------------------------------------------------------
-- 6. 정리 / 통계 갱신
--   VACUUM 으로 visibility map 까지 채워 둬야 인덱스 온리 스캔이 힙을 다시 읽지 않는다
--   (대량 적재 직후 ANALYZE 만 하면 측정 결과가 실제보다 나쁘게 나온다).
-- ---------------------------------------------------------------------
DROP TABLE product_pop;

VACUUM (ANALYZE) brand, "user", product, product_like, product_stat;

-- ---------------------------------------------------------------------
-- 7. 확인
-- ---------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM brand)                                AS brands,
       (SELECT COUNT(*) FROM "user")                               AS users,
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
