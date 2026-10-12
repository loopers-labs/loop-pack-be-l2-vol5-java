DELIMITER $$

DROP PROCEDURE IF EXISTS seed_uniform $$

CREATE PROCEDURE seed_uniform()
BEGIN
    DECLARE v_base_date DATETIME(6) DEFAULT '2026-01-01 00:00:00.000000';

    SET SESSION cte_max_recursion_depth = 10000;

    TRUNCATE TABLE product_like;
    TRUNCATE TABLE product;
    TRUNCATE TABLE brand;

    INSERT INTO brand (id, name, description, created_at, updated_at, deleted_at)
    WITH RECURSIVE seq (n) AS (
        SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100
    )
    SELECT n, CONCAT('brand-', n), NULL, v_base_date, v_base_date, NULL
      FROM seq;

    INSERT INTO product (id, brand_id, name, price, quantity, created_at, updated_at, deleted_at)
    WITH RECURSIVE seq (n) AS (
        SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000
    )
    SELECT n,
           (n % 100) + 1,
           CONCAT('product-', n),
           ((n * 37) % 100000) + 100,
           (n * 7) % 50,
           v_base_date + INTERVAL n SECOND,
           v_base_date + INTERVAL n SECOND,
           NULL
      FROM seq;

    INSERT INTO product_like (user_id, product_id, created_at, updated_at)
    WITH RECURSIVE seq (n) AS (
        SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000
    ),
    users AS (
        SELECT n FROM seq WHERE n <= 300
    ),
    like_counts AS (
        SELECT n AS product_id,
               CASE
                   WHEN n % 1000 = 0 THEN 300
                   WHEN n % 4 = 0 THEN 0
                   ELSE (n * 13) % 20
               END AS like_count
          FROM seq
    )
    SELECT u.n,
           c.product_id,
           v_base_date + INTERVAL c.product_id SECOND,
           v_base_date + INTERVAL c.product_id SECOND
      FROM like_counts c
      JOIN users u ON u.n <= c.like_count;
END $$

DELIMITER ;