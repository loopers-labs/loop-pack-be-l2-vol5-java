DELIMITER $$

DROP PROCEDURE IF EXISTS seed_skewed $$

CREATE PROCEDURE seed_skewed(IN p_total INT)
BEGIN
    DECLARE v_base_date DATETIME(6) DEFAULT '2026-01-01 00:00:00.000000';
    DECLARE v_brand_count INT DEFAULT 5000;
    DECLARE v_brand_exponent DOUBLE DEFAULT 0.7;
    DECLARE v_user_count INT DEFAULT 200000;
    DECLARE v_harmonic DOUBLE;
    DECLARE v_like_scale DOUBLE;
    DECLARE v_from INT DEFAULT 1;

    SET SESSION cte_max_recursion_depth = p_total;

    TRUNCATE TABLE product_like;
    TRUNCATE TABLE product_like_count;
    TRUNCATE TABLE product;
    TRUNCATE TABLE brand;

    DROP TABLE IF EXISTS seed_seq, seed_brand, seed_product;

    CREATE TABLE seed_seq (n INT PRIMARY KEY);
    INSERT INTO seed_seq (n)
    WITH RECURSIVE seq (n) AS (
        SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < p_total
    )
    SELECT n FROM seq;

    SELECT SUM(1e0 / POW(n, v_brand_exponent)) INTO v_harmonic FROM seed_seq WHERE n <= v_brand_count;

    CREATE TABLE seed_brand (k INT PRIMARY KEY, size INT NOT NULL);
    INSERT INTO seed_brand (k, size)
    SELECT k, hi - LAG(hi, 1, 0) OVER (ORDER BY k)
      FROM (
          SELECT n AS k, ROUND(p_total * SUM(1e0 / POW(n, v_brand_exponent)) OVER (ORDER BY n) / v_harmonic) AS hi
            FROM seed_seq
           WHERE n <= v_brand_count
      ) cumulative;

    INSERT INTO brand (id, name, description, created_at, updated_at, deleted_at)
    SELECT k,
           CONCAT('brand-', k),
           NULL,
           v_base_date,
           v_base_date,
           CASE WHEN k IN (300, 700) THEN v_base_date + INTERVAL 400 DAY ELSE NULL END
      FROM seed_brand;

    SET v_like_scale = POW(0.78 * p_total, 0.85);

    CREATE TABLE seed_product (
        id         INT PRIMARY KEY,
        brand_id   INT NOT NULL,
        pop_rank   INT NOT NULL,
        like_count INT NOT NULL,
        KEY idx_seed_product_pop_rank (pop_rank)
    );
    INSERT INTO seed_product (id, brand_id, pop_rank, like_count)
    SELECT id, brand_id, pop_rank, FLOOR(v_like_scale / POW(pop_rank, 0.85))
      FROM (
          SELECT id, brand_id, ROW_NUMBER() OVER (ORDER BY CRC32(CONCAT('pop:', id)), id) AS pop_rank
            FROM (
                SELECT ROW_NUMBER() OVER (ORDER BY CRC32(CONCAT('pid:', b.k, ':', q.n)), b.k, q.n) AS id,
                       b.k AS brand_id
                  FROM seed_brand b
                  STRAIGHT_JOIN seed_seq q ON q.n <= b.size
            ) placed
      ) ranked;

    INSERT INTO product (id, brand_id, name, price, quantity, created_at, updated_at, deleted_at)
    SELECT p.id,
           p.brand_id,
           CONCAT('product-', p.id),
           LEAST(60000, GREATEST(3000, ROUND(
               13000 * EXP(0.6
                   * SQRT(-2 * LN((CRC32(CONCAT('u1:', p.id)) + 1) / 4294967297))
                   * COS(2 * PI() * CRC32(CONCAT('u2:', p.id)) / 4294967296)),
               -2))),
           CRC32(CONCAT('qty:', p.id)) % 100,
           v_base_date + INTERVAL p.id SECOND,
           v_base_date + INTERVAL p.id SECOND,
           CASE
               WHEN p.brand_id IN (300, 700) OR CRC32(CONCAT('del:', p.id)) % 100 < 12
                   THEN v_base_date + INTERVAL 400 DAY
               ELSE NULL
           END
      FROM seed_product p;

    WHILE v_from <= p_total DO
        INSERT INTO product_like (user_id, product_id, created_at, updated_at)
        SELECT MOD(CRC32(CONCAT('uo:', p.id)) + q.n * 7919, v_user_count) + 1,
               p.id,
               v_base_date + INTERVAL p.id SECOND + INTERVAL q.n SECOND,
               v_base_date + INTERVAL p.id SECOND + INTERVAL q.n SECOND
          FROM seed_product p
          STRAIGHT_JOIN seed_seq q ON q.n <= p.like_count
         WHERE p.pop_rank BETWEEN v_from AND v_from + 49999;
        SET v_from = v_from + 50000;
    END WHILE;

    INSERT INTO product_like_count (product_id, like_count)
    SELECT product_id, COUNT(*) FROM product_like GROUP BY product_id;

    DROP TABLE seed_seq, seed_brand, seed_product;
END $$

DELIMITER ;