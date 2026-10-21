-- 쿼리 성능 측정용 대량 데이터 생성 (MySQL 8.0)
-- 실행하면 users·brands·products·product_likes를 비우고 다시 채운다. run.sh seed가 아래 세션 변수를 먼저 지정한다.
--   @perf_brands   브랜드 수            (기본 200)
--   @perf_products 상품 수              (기본 100,000)
--   @perf_likes    좋아요 시도 수       (기본 1,000,000, 같은 사용자-상품 쌍은 버려서 실제 행 수는 조금 적다)
--   @perf_users    사용자 수            (기본 50,000, users 1~N을 만들고 좋아요에 쓴다)
--   @perf_chunk    한 번에 넣고 커밋할 행 수 (기본 100,000)
--   @perf_sleep    청크 사이 쉬는 시간(초)   (기본 0)
--
-- 큰 트랜잭션 하나로 넣으면 undo 로그·버퍼 풀이 한꺼번에 부풀어 DB와 노트북이 함께 느려진다.
-- 그래서 청크 단위로 넣고 바로 커밋하며, 필요하면 청크 사이에 쉬어 I/O를 나눈다.
-- 같은 데이터를 다시 만들 수 있도록 청크마다 고정 시드(청크 번호 기반)로 RAND를 쓴다.

SET @perf_brands   = COALESCE(@perf_brands, 200);
SET @perf_products = COALESCE(@perf_products, 100000);
SET @perf_likes    = COALESCE(@perf_likes, 1000000);
SET @perf_users    = COALESCE(@perf_users, 50000);
SET @perf_chunk    = COALESCE(@perf_chunk, 100000);
SET @perf_sleep    = COALESCE(@perf_sleep, 0);

SET SESSION sql_log_bin = 0;
SET SESSION autocommit = 1;

-- 각 JpaEntity를 Hibernate(MySQL)가 매핑하는 스키마와 같은 컬럼명·타입·NULL 여부로 만든다.
--   Long @Id IDENTITY → bigint auto_increment, long → bigint, int → int, boolean → bit,
--   Instant → datetime(6), String(length=100) → varchar(100). 모든 컬럼이 NOT NULL이다.
-- 차이: product_likes 유일 제약 이름은 Hibernate가 임의 이름(UK...)을 붙이므로 여기서만 이름을 정했다.
-- like_count(@ColumnDefault 0)와 보조 인덱스는 엔티티의 @Index와 같은 이름·열·정렬 방향으로 만든다(설계 17·18절).
-- 테이블은 매번 지우고 새로 만든다. 예전 seed로 만든 테이블(like_count·인덱스 없음)이 남아 있어도 현재 스키마로 맞춰진다.
DROP TABLE IF EXISTS product_likes, products, brands, users;
CREATE TABLE IF NOT EXISTS users (
    id         BIGINT       NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS brands (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    deleted    BIT          NOT NULL,
    name       VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS products (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    brand_id   BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    deleted    BIT          NOT NULL,
    name       VARCHAR(100) NOT NULL,
    price      BIGINT       NOT NULL,
    stock      INT          NOT NULL,
    like_count BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_products_brand_price (brand_id, price ASC, id DESC),
    KEY idx_products_brand_created (brand_id, created_at),
    KEY idx_products_created (created_at DESC, id DESC),
    KEY idx_products_price (price ASC, id DESC),
    KEY idx_products_brand_likes (brand_id, like_count DESC, id DESC),
    KEY idx_products_likes (like_count DESC, id DESC)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_likes (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_likes_user_product (user_id, product_id),
    KEY idx_product_likes_product (product_id)
) ENGINE = InnoDB;

-- 대량 삽입 동안은 보조 인덱스를 떼어 둔다. 행마다 6개 보조 인덱스의 무작위 위치에 끼워 넣으면
-- 인덱스가 버퍼 풀(128MB)보다 커지는 순간부터 디스크 읽기가 폭증한다(1,000만 규모에서 청크당 50초 이상).
-- 데이터를 다 넣은 뒤 한 번에 만들면 정렬 후 순서대로 쌓아(sorted index build) 훨씬 빠르다. 최종 스키마는 위 CREATE TABLE과 같다.
ALTER TABLE products
    DROP INDEX idx_products_brand_price, DROP INDEX idx_products_brand_created, DROP INDEX idx_products_created,
    DROP INDEX idx_products_price, DROP INDEX idx_products_brand_likes, DROP INDEX idx_products_likes;
ALTER TABLE product_likes DROP INDEX idx_product_likes_product;

-- 청크 크기만큼의 숫자 시퀀스 (0 ~ chunk-1)
DROP TABLE IF EXISTS perf_digits;
CREATE TABLE perf_digits (d INT PRIMARY KEY) ENGINE = InnoDB;
INSERT INTO perf_digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);
DROP TABLE IF EXISTS perf_seq;
CREATE TABLE perf_seq (n INT PRIMARY KEY) ENGINE = InnoDB;
INSERT INTO perf_seq (n)
SELECT t.n FROM (
    SELECT a.d + b.d * 10 + c.d * 100 + d.d * 1000 + e.d * 10000 + f.d * 100000 AS n
    FROM perf_digits a, perf_digits b, perf_digits c, perf_digits d, perf_digits e, perf_digits f
) t
WHERE t.n < LEAST(@perf_chunk, 1000000);
DROP TABLE perf_digits;

DROP PROCEDURE IF EXISTS perf_fill;
DELIMITER $$
-- p_sql의 {base}·{limit}·{s1}~{s4}를 청크마다 치환해 실행한다. 시드를 SQL 리터럴로 넣어야 RAND가 문장 안에서 한 번만 초기화된다.
CREATE PROCEDURE perf_fill(IN p_label VARCHAR(50), IN p_total BIGINT, IN p_seed INT, IN p_sql TEXT)
BEGIN
    DECLARE v_base BIGINT DEFAULT 0;
    DECLARE v_chunk_no INT DEFAULT 0;
    DECLARE v_limit BIGINT;
    WHILE v_base < p_total DO
        SET v_limit = LEAST(@perf_chunk, p_total - v_base);
        SET @perf_stmt = REPLACE(REPLACE(p_sql, '{base}', v_base), '{limit}', v_limit);
        SET @perf_stmt = REPLACE(@perf_stmt, '{s1}', p_seed * 100000 + v_chunk_no * 4 + 1);
        SET @perf_stmt = REPLACE(@perf_stmt, '{s2}', p_seed * 100000 + v_chunk_no * 4 + 2);
        SET @perf_stmt = REPLACE(@perf_stmt, '{s3}', p_seed * 100000 + v_chunk_no * 4 + 3);
        SET @perf_stmt = REPLACE(@perf_stmt, '{s4}', p_seed * 100000 + v_chunk_no * 4 + 4);
        PREPARE perf_s FROM @perf_stmt;
        EXECUTE perf_s;
        DEALLOCATE PREPARE perf_s;
        SET v_base = v_base + v_limit;
        SET v_chunk_no = v_chunk_no + 1;
        IF MOD(v_chunk_no, 10) = 0 OR v_base >= p_total THEN
            SELECT CONCAT('[seed] ', p_label, ' ', v_base, ' / ', p_total) AS progress;
        END IF;
        IF @perf_sleep > 0 THEN
            DO SLEEP(@perf_sleep);
        END IF;
    END WHILE;
END$$
DELIMITER ;

-- 사용자 1~N (고객 조회 API가 X-USER-ID 사용자 존재를 확인한다)
CALL perf_fill('users', @perf_users, 4,
    'INSERT INTO users (id) SELECT {base} + n + 1 FROM perf_seq WHERE n < {limit}');

-- 브랜드 (20, 60, 100, 140, 180번 삭제 — 기본 200개 중 5개)
-- 측정에 쓰는 큰 브랜드 1과 작은 브랜드 200은 삭제되지 않게 고른다.
CALL perf_fill('brands', @perf_brands, 1,
    'INSERT INTO brands (id, deleted, name)
     SELECT {base} + n + 1, ({base} + n + 1) % 40 = 20, CONCAT(''브랜드-'', {base} + n + 1)
     FROM perf_seq WHERE n < {limit}');

-- 상품
-- brand_id: 앞 번호 브랜드에 상품이 몰리도록 제곱 분포
-- created_at: 2025-01-01부터 1년 사이 무작위 (ID 순서와 생성 시각 순서가 다르게)
-- price: 1,000 ~ 500,000원 (100원 단위), deleted: 약 5%
SET @perf_sql = CONCAT(
    'INSERT INTO products (id, brand_id, created_at, deleted, name, price, stock)
     SELECT {base} + n + 1,
            1 + FLOOR(', @perf_brands, ' * POW(RAND({s1}), 2)),
            TIMESTAMP(''2025-01-01 00:00:00'') + INTERVAL FLOOR(RAND({s2}) * 365 * 24 * 3600) SECOND,
            RAND({s3}) < 0.05,
            CONCAT(''상품-'', {base} + n + 1),
            (10 + FLOOR(RAND({s4}) * 4991)) * 100,
            ({base} + n) % 1000
     FROM perf_seq WHERE n < {limit}');
CALL perf_fill('products', @perf_products, 2, @perf_sql);

-- 좋아요 (같은 사용자-상품 쌍은 무시)
-- user_id: 시도 순번에 비례해 1번 사용자부터 차례로 배정 (사용자당 평균 likes/users건)
-- product_id: 앞 번호 상품에 좋아요가 몰리도록 4제곱 분포
-- 유일 인덱스(user_id, product_id) 순서로 정렬해 넣으면 인덱스 끝에 이어 붙게 되어,
-- 무작위 위치 삽입보다 디스크 I/O·redo 로그 부담이 크게 줄어든다.
SET @perf_sql = CONCAT(
    'INSERT IGNORE INTO product_likes (user_id, product_id)
     SELECT t.user_id, t.product_id FROM (
         SELECT 1 + FLOOR(({base} + n) * ', @perf_users, ' / ', @perf_likes, ') AS user_id,
                1 + FLOOR(', @perf_products, ' * POW(RAND({s1}), 4)) AS product_id
         FROM perf_seq WHERE n < {limit}
     ) t
     ORDER BY t.user_id, t.product_id');
CALL perf_fill('product_likes', @perf_likes, 3, @perf_sql);

-- 좋아요 수 채우기에 product_id 범위 읽기가 필요해 이 인덱스를 먼저 만든다.
ALTER TABLE product_likes ADD INDEX idx_product_likes_product (product_id);

-- 좋아요 수 초기 채우기 (설계 18절): 상품 id 구간마다 짧은 트랜잭션으로 실제 관계 수를 like_count에 넣는다.
-- 구간 집계는 product_likes(product_id) 인덱스 범위를 읽고, 좋아요가 없는 상품은 기본값 0을 그대로 둔다.
CALL perf_fill('like_count', @perf_products, 5,
    'UPDATE products p
     JOIN (SELECT product_id, COUNT(*) AS cnt FROM product_likes
           WHERE product_id > {base} AND product_id <= {base} + {limit}
           GROUP BY product_id) t ON t.product_id = p.id
     SET p.like_count = t.cnt');

-- 3주차 계약: 삭제된 브랜드의 상품은 모두 삭제 상태다. 삭제 브랜드 상품만 청크 없이 한 번에 바꾼다
-- (기본 규모 약 2천 건, 1,000만 규모 약 20만 건).
UPDATE products SET deleted = 1
WHERE deleted = 0 AND brand_id IN (SELECT id FROM brands WHERE deleted = 1);

-- 떼어 둔 상품 보조 인덱스를 한 번에 만든다 (위 CREATE TABLE과 같은 이름·열·정렬 방향).
ALTER TABLE products
    ADD INDEX idx_products_brand_price (brand_id, price ASC, id DESC),
    ADD INDEX idx_products_brand_created (brand_id, created_at),
    ADD INDEX idx_products_created (created_at DESC, id DESC),
    ADD INDEX idx_products_price (price ASC, id DESC),
    ADD INDEX idx_products_brand_likes (brand_id, like_count DESC, id DESC),
    ADD INDEX idx_products_likes (like_count DESC, id DESC);

DROP PROCEDURE perf_fill;
DROP TABLE perf_seq;

ANALYZE TABLE users, brands, products, product_likes;

-- 검증: 각 테이블 건수와 모델 규칙 위반 건수 (위반은 0이어야 한다)
SELECT 'users' AS item, COUNT(*) AS cnt FROM users
UNION ALL SELECT 'brands', COUNT(*) FROM brands
UNION ALL SELECT 'brands(deleted)', COUNT(*) FROM brands WHERE deleted = 1
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'products(deleted=false)', COUNT(*) FROM products WHERE deleted = 0
UNION ALL SELECT 'product_likes', COUNT(*) FROM product_likes
UNION ALL SELECT 'violation:삭제 브랜드의 미삭제 상품', COUNT(*)
    FROM products p JOIN brands b ON b.id = p.brand_id WHERE b.deleted = 1 AND p.deleted = 0
UNION ALL SELECT 'violation:없는 사용자의 좋아요', COUNT(*)
    FROM product_likes l LEFT JOIN users u ON u.id = l.user_id WHERE u.id IS NULL
UNION ALL SELECT 'violation:like_count와 실제 좋아요 수가 다른 상품', COUNT(*)
    FROM products p LEFT JOIN (SELECT product_id, COUNT(*) AS cnt FROM product_likes GROUP BY product_id) t
        ON t.product_id = p.id
    WHERE p.like_count <> COALESCE(t.cnt, 0)
UNION ALL SELECT 'brand 1 products(deleted=false)', COUNT(*) FROM products WHERE brand_id = 1 AND deleted = 0
UNION ALL SELECT 'brand 200 products(deleted=false)', COUNT(*) FROM products WHERE brand_id = 200 AND deleted = 0
UNION ALL SELECT 'product 1 likes', COUNT(*) FROM product_likes WHERE product_id = 1;
