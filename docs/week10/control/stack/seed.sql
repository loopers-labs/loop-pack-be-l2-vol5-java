USE loopers;
SET NAMES utf8mb4;
INSERT INTO users (id) VALUES (1001), (1002), (1003);
INSERT INTO brands (id, deleted, name, created_at, updated_at)
VALUES (1, 0, '음료', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
INSERT INTO products (id, brand_id, deleted, name, price, stock, created_at, updated_at)
VALUES (1, 1, 0, '커피', 1500, 100, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (2, 1, 0, '차', 1000, 100, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (3, 1, 0, '물', 500, 10000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
