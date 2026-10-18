-- =====================================================================
-- 재고 테이블 분리 (MySQL, DR-38)
--
-- product_stock(현재 재고)·product_stock_ledger(원장)를 만들고, product.stock 의 현재 값을 옮긴다.
--   product_stock        : 상품마다 한 행, available = product.stock
--   product_stock_ledger : stock > 0 인 상품마다 ADJUSTMENT 한 행, quantity = product.stock (0 → 현재 값으로 맞춘 조정)
-- product.stock 컬럼은 지우지 않는다. 앱이 컬럼을 지울 때까지 같은 값으로 함께 쓴다 (DR-38).
--
-- 순서
--   운영(ddl-auto: none)은 새 앱을 배포하기 전에, 옛 앱의 재고 쓰기(확정·재고 변경·상품 생성)를 멈춘 상태에서 실행한다.
--   옮긴 뒤 옛 앱이 product.stock 만 바꾸면 두 테이블과 어긋난다.
--   local 프로필은 ddl-auto: update 가 테이블을 만들지만 행은 만들지 않으므로 이 스크립트가 필요하다.
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week5/product-stock.sql
--
-- 주의: 두 테이블을 TRUNCATE 하고 시작한다. 새 앱 배포 뒤에 다시 돌리면 그 사이 쌓인 원장이 사라진다.
-- =====================================================================

SET NAMES utf8mb4;

-- 엔티티(ProductStockModel, ProductStockLedgerModel)와 같은 이름·컬럼
CREATE TABLE IF NOT EXISTS product_stock (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    available  INT         NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_product_stock_product UNIQUE (product_id)
);

CREATE TABLE IF NOT EXISTS product_stock_ledger (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    type       VARCHAR(20) NOT NULL,
    quantity   INT         NOT NULL,
    order_id   BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_product_stock_ledger_order UNIQUE (order_id, product_id, type)
);

SET SESSION sql_log_bin = 0;

TRUNCATE TABLE product_stock;
TRUNCATE TABLE product_stock_ledger;

-- 상품 id 50만 개씩 끊어 넣는다. 한 트랜잭션에 전 상품을 넣지 않으려고.
--   product_stock.created_at: 상품 생성 시각 (상품과 함께 만들어지는 행) / 원장 created_at: 이관 시각 (그때 남긴 조정이므로)
DROP PROCEDURE IF EXISTS backfill_product_stock;
DELIMITER $$
CREATE PROCEDURE backfill_product_stock()
BEGIN
    DECLARE lo BIGINT;
    DECLARE hi BIGINT;
    DECLARE now6 DATETIME(6) DEFAULT UTC_TIMESTAMP(6);
    SELECT MIN(id), MAX(id) INTO lo, hi FROM product;
    WHILE lo <= hi DO
        INSERT INTO product_stock (product_id, available, created_at, updated_at, deleted_at)
        SELECT p.id, p.stock, p.created_at, now6, NULL
        FROM product p
        WHERE p.id BETWEEN lo AND lo + 499999
        ORDER BY p.id;

        INSERT INTO product_stock_ledger (product_id, type, quantity, order_id, created_at, updated_at, deleted_at)
        SELECT p.id, 'ADJUSTMENT', p.stock, NULL, now6, now6, NULL
        FROM product p
        WHERE p.id BETWEEN lo AND lo + 499999
          AND p.stock > 0
        ORDER BY p.id;
        SET lo = lo + 500000;
    END WHILE;
END$$
DELIMITER ;

CALL backfill_product_stock();
DROP PROCEDURE backfill_product_stock;

ANALYZE TABLE product_stock, product_stock_ledger;

-- ---------------------------------------------------------------------
-- 검증. 아래 값이 모두 맞아야 한다.
--   stocks = products, available_sum = ledger_sum = legacy_sum, stock_mismatch = 0, ledger_mismatch = 0
-- ---------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM product)                    AS products,
       (SELECT COUNT(*) FROM product_stock)              AS stocks,
       (SELECT SUM(stock) FROM product)                  AS legacy_sum,
       (SELECT SUM(available) FROM product_stock)        AS available_sum,
       (SELECT SUM(quantity) FROM product_stock_ledger)  AS ledger_sum;

SELECT COUNT(*) AS stock_mismatch
FROM product p
LEFT JOIN product_stock s ON s.product_id = p.id
WHERE s.product_id IS NULL OR s.available <> p.stock;

SELECT COUNT(*) AS ledger_mismatch
FROM product_stock s
LEFT JOIN (
    SELECT product_id, SUM(quantity) AS total FROM product_stock_ledger GROUP BY product_id
) l ON l.product_id = s.product_id
WHERE s.available <> COALESCE(l.total, 0);
