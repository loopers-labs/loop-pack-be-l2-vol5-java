-- MySQL 8.0용 증분 성능 seed v2 (독립 salt/1,000순번 블록 순위). 현재 Java 모델의 컬럼 이름을 기준으로 작성했다.
-- 2026-10-16 실제 DDL 대조 후 init/append 실행·D0 생성 검증 완료. more_likes는 미실행.
-- 실행 원본: experiments/artifacts/d0-20261016/ (설치 당시 SQL 사본 보존).
-- SQL 콘솔에서 대상 schema를 먼저 선택한다. USE/DB 생성/기존 데이터 삭제는 하지 않는다.
-- seed 실행 중에는 앱의 쓰기와 다른 seed를 중단하고, autocommit 세션에서 CALL한다.
-- 앱 재시작의 ddl-auto=create가 기존 데이터와 이 관리 테이블을 불일치시키지 않게 한다.
-- 프로시저 설치 DDL은 트랜잭션 밖에서 실행한다.
-- v2 dataset명은 -v2로 끝낸다. v1 자료에 이어 append하지 않는다.
-- 공용 writer lock 이름은 구버전과의 동시 실행 방지를 위해 v1을 유지한다.

CREATE TABLE IF NOT EXISTS w4_seed_number (
    n INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

-- 1..10,000: 재귀 CTE 제한이나 백만 번의 단건 INSERT 대신 집합 생성에 사용한다.
INSERT IGNORE INTO w4_seed_number (n)
SELECT 1 + a.d + 10*b.d + 100*c.d + 1000*d.d
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
      UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
      UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
      UNION ALL SELECT 8 UNION ALL SELECT 9) c
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
      UNION ALL SELECT 8 UNION ALL SELECT 9) d;

CREATE TABLE IF NOT EXISTS w4_seed_dataset (
    dataset VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    brand_base BIGINT NOT NULL,
    brand_count INT NOT NULL,
    user_base BIGINT NOT NULL,
    user_count INT NOT NULL,
    hot_brand_percent INT NOT NULL,
    last_product_seq BIGINT NOT NULL DEFAULT 0,
    seed_epoch DATETIME(6) NOT NULL
) ENGINE=InnoDB;

-- 실제 상품 ID와 논리 순번을 분리한다. ID가 1부터라고 가정하지 않는다.
CREATE TABLE IF NOT EXISTS w4_seed_product (
    dataset VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_seq BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    user_offset INT NOT NULL,
    seed_like_count INT NOT NULL,
    PRIMARY KEY (dataset, product_seq),
    UNIQUE KEY uk_w4_seed_product_id (product_id)
) ENGINE=InnoDB;

DELIMITER $$

DROP PROCEDURE IF EXISTS w4_seed_init$$
CREATE PROCEDURE w4_seed_init(
    IN p_dataset VARCHAR(32),
    IN p_brands INT,
    IN p_users INT,
    IN p_hot_brand_percent INT
)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_locked INT DEFAULT 0;
    DECLARE v_brand_base BIGINT;
    DECLARE v_user_base BIGINT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_size INT;
    DECLARE v_epoch DATETIME(6) DEFAULT '2026-01-01 00:00:00.000000';
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_locked = 1 THEN DO RELEASE_LOCK('w4_seed_writer_v1'); END IF;
        RESIGNAL;
    END;

    IF p_dataset IS NULL OR p_dataset NOT REGEXP '^[A-Za-z0-9_-]{1,32}$'
       OR RIGHT(p_dataset, 3) <> '-v2'
       OR p_brands IS NULL OR p_brands < 2 OR p_brands > 10000
       OR p_users IS NULL OR p_users < 1 OR p_users > 1000000
       OR p_hot_brand_percent IS NULL OR p_hot_brand_percent < 0
       OR p_hot_brand_percent > 80 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid dataset/init parameters';
    END IF;
    SELECT GET_LOCK('w4_seed_writer_v1', 5) INTO v_locked;
    IF v_locked IS NULL OR v_locked <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Another seed writer is active';
    END IF;
    START TRANSACTION;
    IF EXISTS (SELECT 1 FROM w4_seed_dataset WHERE dataset = p_dataset) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Dataset exists; use append, not init';
    END IF;
    SELECT COALESCE(MAX(id), 0) INTO v_brand_base FROM brand;
    SELECT COALESCE(MAX(id), 0) INTO v_user_base FROM users;
    INSERT INTO brand (id, name, created_at, updated_at, deleted_at)
    SELECT v_brand_base+n, CONCAT('w4-', p_dataset, '-brand-', n), v_epoch, v_epoch, NULL
    FROM w4_seed_number WHERE n <= p_brands ORDER BY n;

    WHILE v_done < p_users DO
        SET v_size = LEAST(10000, p_users-v_done);
        INSERT INTO users (id, created_at, updated_at, deleted_at)
        SELECT v_user_base+v_done+n, v_epoch, v_epoch, NULL
        FROM w4_seed_number WHERE n <= v_size ORDER BY n;
        -- 기존 UserFixture와 같이 잔액 0의 Point도 함께 준비한다.
        INSERT INTO point (user_id, balance, created_at, updated_at, deleted_at)
        SELECT v_user_base+v_done+n, 0, v_epoch, v_epoch, NULL
        FROM w4_seed_number WHERE n <= v_size ORDER BY n;
        SET v_done = v_done+v_size;
    END WHILE;
    INSERT INTO w4_seed_dataset
        (dataset, brand_base, brand_count, user_base, user_count,
         hot_brand_percent, last_product_seq, seed_epoch)
    VALUES (p_dataset, v_brand_base, p_brands, v_user_base, p_users,
            p_hot_brand_percent, 0, v_epoch);
    COMMIT;
    DO RELEASE_LOCK('w4_seed_writer_v1');
    SET v_locked = 0;
    SELECT dataset, brand_base+1 AS first_brand_id, user_base+1 AS first_user_id,
           brand_count, user_count, last_product_seq
    FROM w4_seed_dataset WHERE dataset = p_dataset;
END$$

DROP PROCEDURE IF EXISTS w4_seed_append$$
CREATE PROCEDURE w4_seed_append(
    IN p_dataset VARCHAR(32),
    IN p_add_products INT,
    IN p_like_scale INT,
    IN p_chunk_products INT
)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_locked INT DEFAULT 0;
    DECLARE v_brand_base BIGINT;
    DECLARE v_brands INT;
    DECLARE v_user_base BIGINT;
    DECLARE v_users INT;
    DECLARE v_hot INT;
    DECLARE v_epoch DATETIME(6);
    DECLARE v_last BIGINT;
    DECLARE v_product_base BIGINT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_size INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_locked = 1 THEN DO RELEASE_LOCK('w4_seed_writer_v1'); END IF;
        RESIGNAL;
    END;

    IF p_dataset IS NULL OR RIGHT(p_dataset, 3) <> '-v2'
       OR p_add_products IS NULL OR p_add_products < 1
       OR p_like_scale IS NULL OR p_like_scale < 0
       OR p_chunk_products IS NULL OR p_chunk_products < 1 OR p_chunk_products > 10000 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid append parameters';
    END IF;
    SELECT GET_LOCK('w4_seed_writer_v1', 5) INTO v_locked;
    IF v_locked IS NULL OR v_locked <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Another seed writer is active';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM w4_seed_dataset WHERE dataset = p_dataset) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Call w4_seed_init first';
    END IF;
    WHILE v_done < p_add_products DO
        START TRANSACTION;
        SELECT brand_base, brand_count, user_base, user_count, hot_brand_percent,
               seed_epoch, last_product_seq
        INTO v_brand_base, v_brands, v_user_base, v_users, v_hot, v_epoch, v_last
        FROM w4_seed_dataset WHERE dataset = p_dataset FOR UPDATE;
        IF p_like_scale > FLOOR(LEAST(v_users, 10000)/30) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Like scale exceeds user/number capacity';
        END IF;
        -- 앱 ddl-auto=create나 별도 삭제로 seed의 부모 행이 사라졌으면 중단한다.
        IF (SELECT COUNT(*) FROM brand
            WHERE id > v_brand_base AND id <= v_brand_base+v_brands
              AND name LIKE CONCAT('w4-', p_dataset, '-brand-%')) <> v_brands
           OR (SELECT COUNT(*) FROM users
               WHERE id > v_user_base AND id <= v_user_base+v_users) <> v_users THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Seed parents changed; inspect schema/data';
        END IF;
        SET v_size = LEAST(p_chunk_products, p_add_products-v_done);
        SELECT COALESCE(MAX(id), 0) INTO v_product_base FROM product;

        INSERT INTO product
            (id, brand_id, name, price, stock_quantity, created_at, updated_at, deleted_at)
        WITH block_rows AS (
            -- 요청 chunk 바깥까지 완전한 1,000순번 블록을 만든 뒤 순위를 정한다.
            -- 마지막 WHERE로 자르므로 chunk/호출 분할이 분포를 바꾸지 않는다.
            SELECT FLOOR(v_last/1000)+blocks.n-1 AS block_no,
                   (FLOOR(v_last/1000)+blocks.n-1)*1000+slots.n AS product_seq
            FROM w4_seed_number blocks CROSS JOIN w4_seed_number slots
            WHERE blocks.n <= FLOOR((v_last+v_size-1)/1000)-FLOOR(v_last/1000)+1
              AND slots.n <= 1000
        ), ranked AS (
            SELECT block_no, product_seq,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:brand:', product_seq),256),product_seq)-1 AS brand_rank,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:price:', product_seq),256),product_seq)-1 AS price_rank,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:likes:', product_seq),256),product_seq)-1 AS like_rank
            FROM block_rows
        )
        SELECT v_product_base+product_seq-v_last,
               v_brand_base + CASE WHEN brand_rank < v_hot*10 THEN 1
                   ELSE 2+MOD(block_no*(1000-v_hot*10)+brand_rank-v_hot*10,v_brands-1) END,
               CONCAT('w4-', p_dataset, '-product-', product_seq),
               (price_rank+1)*100, 0,
               DATE_ADD(v_epoch, INTERVAL product_seq SECOND),
               DATE_ADD(v_epoch, INTERVAL product_seq SECOND), NULL
        FROM ranked WHERE product_seq > v_last AND product_seq <= v_last+v_size
        ORDER BY product_seq;

        -- 완전한 1,000순번마다 각 bucket 100개, 좋아요 합계 10,000개(배수 1).
        INSERT INTO w4_seed_product
            (dataset, product_seq, product_id, user_offset, seed_like_count)
        WITH block_rows AS (
            -- 요청 chunk 바깥까지 완전한 1,000순번 블록을 만든 뒤 순위를 정한다.
            -- 마지막 WHERE로 자르므로 chunk/호출 분할이 분포를 바꾸지 않는다.
            SELECT FLOOR(v_last/1000)+blocks.n-1 AS block_no,
                   (FLOOR(v_last/1000)+blocks.n-1)*1000+slots.n AS product_seq
            FROM w4_seed_number blocks CROSS JOIN w4_seed_number slots
            WHERE blocks.n <= FLOOR((v_last+v_size-1)/1000)-FLOOR(v_last/1000)+1
              AND slots.n <= 1000
        ), ranked AS (
            SELECT block_no, product_seq,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:brand:', product_seq),256),product_seq)-1 AS brand_rank,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:price:', product_seq),256),product_seq)-1 AS price_rank,
                   ROW_NUMBER() OVER (PARTITION BY block_no ORDER BY
                       SHA2(CONCAT('w4-seed-v2:likes:', product_seq),256),product_seq)-1 AS like_rank
            FROM block_rows
        )
        SELECT p_dataset, product_seq, v_product_base+product_seq-v_last,
               MOD(CONV(SUBSTRING(SHA2(CONCAT('w4-seed-v2:user:',product_seq),256),1,8),16,10),v_users),
               p_like_scale * CASE MOD(like_rank,10)
                   WHEN 0 THEN 0 WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 5
                   WHEN 4 THEN 5 WHEN 5 THEN 10 WHEN 6 THEN 12 WHEN 7 THEN 15
                   WHEN 8 THEN 20 ELSE 30 END
        FROM ranked WHERE product_seq > v_last AND product_seq <= v_last+v_size
        ORDER BY product_seq;

        INSERT INTO product_like (user_id, product_id, created_at, updated_at, deleted_at)
        SELECT v_user_base+1+MOD(s.user_offset+nums.n-1,v_users), s.product_id,
               DATE_ADD(v_epoch, INTERVAL s.product_seq SECOND),
               DATE_ADD(v_epoch, INTERVAL s.product_seq SECOND), NULL
        FROM w4_seed_product s
        JOIN w4_seed_number nums ON nums.n <= s.seed_like_count
        WHERE s.dataset = p_dataset AND s.product_seq > v_last
              AND s.product_seq <= v_last+v_size
        ORDER BY s.product_seq, nums.n;
        UPDATE w4_seed_dataset SET last_product_seq = v_last+v_size WHERE dataset = p_dataset;
        COMMIT;
        SET v_done = v_done+v_size;
    END WHILE;
    DO RELEASE_LOCK('w4_seed_writer_v1');
    SET v_locked = 0;
    SELECT dataset, last_product_seq AS products,
           (SELECT COALESCE(SUM(seed_like_count),0) FROM w4_seed_product
            WHERE dataset = p_dataset) AS seeded_likes
    FROM w4_seed_dataset WHERE dataset = p_dataset;
END$$

-- 상품 수를 고정하고 집계량만 늘리는 추가 실험용. API로 관계를 바꾼 후에는 쓰지 않는다.
DROP PROCEDURE IF EXISTS w4_seed_more_likes$$
CREATE PROCEDURE w4_seed_more_likes(
    IN p_dataset VARCHAR(32),
    IN p_first_product_seq BIGINT,
    IN p_last_product_seq BIGINT,
    IN p_add_each INT,
    IN p_chunk_products INT
)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_locked INT DEFAULT 0;
    DECLARE v_users INT;
    DECLARE v_user_base BIGINT;
    DECLARE v_max_seq BIGINT;
    DECLARE v_epoch DATETIME(6);
    DECLARE v_first BIGINT;
    DECLARE v_last BIGINT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_locked = 1 THEN DO RELEASE_LOCK('w4_seed_writer_v1'); END IF;
        RESIGNAL;
    END;

    IF p_first_product_seq IS NULL OR p_first_product_seq < 1
       OR p_last_product_seq IS NULL OR p_last_product_seq < p_first_product_seq
       OR p_add_each IS NULL OR p_add_each < 1 OR p_add_each > 10000
       OR p_chunk_products IS NULL OR p_chunk_products < 1 OR p_chunk_products > 10000 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid more-likes parameters';
    END IF;
    SELECT GET_LOCK('w4_seed_writer_v1', 5) INTO v_locked;
    IF v_locked IS NULL OR v_locked <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Another seed writer is active';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM w4_seed_dataset WHERE dataset = p_dataset) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Call w4_seed_init first';
    END IF;
    SET v_first = p_first_product_seq;
    SELECT user_base, user_count, last_product_seq, seed_epoch
    INTO v_user_base, v_users, v_max_seq, v_epoch
    FROM w4_seed_dataset WHERE dataset = p_dataset;
    IF p_last_product_seq > v_max_seq THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Product sequence does not exist';
    END IF;
    IF (SELECT COUNT(*) FROM w4_seed_product
        WHERE dataset = p_dataset AND product_seq BETWEEN p_first_product_seq AND p_last_product_seq)
          <> p_last_product_seq-p_first_product_seq+1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Product mapping missing';
    END IF;
    IF EXISTS (SELECT 1 FROM w4_seed_product
               WHERE dataset = p_dataset
                     AND product_seq BETWEEN p_first_product_seq AND p_last_product_seq
                     AND seed_like_count+p_add_each > v_users) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Per-product likes exceed unique user pool';
    END IF;
    -- 검증도 상품마다 상관 COUNT하지 않고 한 번의 집계와 비교한다.
    -- 준비 중 앱 쓰기가 없다는 전제와 seed writer lock 아래에서 수행한다.
    IF EXISTS (SELECT 1 FROM w4_seed_product s
               LEFT JOIN product p ON p.id=s.product_id
               LEFT JOIN (SELECT product_id, COUNT(*) AS actual_count
                          FROM product_like GROUP BY product_id) counts
                      ON counts.product_id=s.product_id
               WHERE s.dataset=p_dataset
                     AND s.product_seq BETWEEN p_first_product_seq AND p_last_product_seq
                     AND (p.id IS NULL OR p.deleted_at IS NOT NULL
                          OR s.seed_like_count <> COALESCE(counts.actual_count,0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Seed product/likes changed; use another dataset';
    END IF;
    IF (SELECT COUNT(*) FROM users WHERE id > v_user_base AND id <= v_user_base+v_users)
           <> v_users THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Seed users changed';
    END IF;
    WHILE v_first <= p_last_product_seq DO
        START TRANSACTION;
        SELECT user_base, user_count, last_product_seq, seed_epoch
        INTO v_user_base, v_users, v_max_seq, v_epoch
        FROM w4_seed_dataset WHERE dataset = p_dataset FOR UPDATE;
        SET v_last = LEAST(p_last_product_seq, v_first+p_chunk_products-1);
        INSERT INTO product_like (user_id, product_id, created_at, updated_at, deleted_at)
        SELECT v_user_base+1+MOD(s.user_offset+s.seed_like_count+nums.n-1,v_users),
               s.product_id, DATE_ADD(v_epoch, INTERVAL s.product_seq SECOND),
               DATE_ADD(v_epoch, INTERVAL s.product_seq SECOND), NULL
        FROM w4_seed_product s JOIN w4_seed_number nums ON nums.n <= p_add_each
        WHERE s.dataset=p_dataset AND s.product_seq BETWEEN v_first AND v_last
        ORDER BY s.product_seq, nums.n;
        UPDATE w4_seed_product SET seed_like_count=seed_like_count+p_add_each
        WHERE dataset=p_dataset AND product_seq BETWEEN v_first AND v_last;
        COMMIT;
        SET v_first=v_last+1;
    END WHILE;
    DO RELEASE_LOCK('w4_seed_writer_v1');
    SET v_locked=0;
    SELECT p_dataset AS dataset, p_first_product_seq AS first_seq,
           p_last_product_seq AS last_seq, p_add_each AS likes_added_per_product;
END$$

DELIMITER ;

-- 사용 예시(주석). 설치만으로 업무 데이터가 증가하지 않는다:
-- CALL w4_seed_init('index-d0-v2', 100, 20000, 20);
-- CALL w4_seed_append('index-d0-v2', 100, 1, 1000);   -- 상품 100 / 부분 블록 좋아요 수는 실제 합계 확인
-- CALL w4_seed_append('index-d0-v2', 1000, 1, 1000);  -- 1,000개 추가: 누적 상품 1,100 / 좋아요 합계 확인
-- CALL w4_seed_append('index-d0-v2', 98900, 1, 1000); -- 누적 상품 100,000 / 좋아요 1,000,000
-- CALL w4_seed_more_likes('index-d0-v2', 10, 10, 1000, 100); -- 인기 상품에 좋아요 1,000개 추가
-- CALL w4_seed_more_likes('index-d0-v2', 1, 100000, 10, 1000); -- 상품 수 고정, 좋아요 집계량만 증가

-- 실제 ID와 입력 확인용(다음 SELECT도 주석으로 두었다):
-- SELECT dataset, brand_base+1 AS hot_brand_id, user_base+1 AS http_user_id,
--        brand_count, user_count, last_product_seq FROM w4_seed_dataset;
-- SELECT product_seq, product_id, seed_like_count FROM w4_seed_product
-- WHERE dataset='index-d0-v2' ORDER BY product_seq LIMIT 20;
