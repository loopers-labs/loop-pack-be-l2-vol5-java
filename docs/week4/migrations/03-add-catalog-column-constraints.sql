-- Execute via apply-catalog-constraints.sh (preflight and existing-definition checks).
ALTER TABLE brand MODIFY name VARCHAR(50) NOT NULL;
ALTER TABLE product MODIFY name VARCHAR(100) NOT NULL;
ALTER TABLE product MODIFY brand_id BIGINT NOT NULL;
ALTER TABLE product_like MODIFY user_id BIGINT NOT NULL;
ALTER TABLE product_like MODIFY product_id BIGINT NOT NULL;
ALTER TABLE brand ADD CONSTRAINT ck_brand_name CHECK (OCTET_LENGTH(CONVERT(name USING utf16)) BETWEEN 2 AND 100 AND ASCII(LEFT(name, 1)) > 32 AND ASCII(RIGHT(name, 1)) > 32);
ALTER TABLE product ADD CONSTRAINT ck_product_name CHECK (OCTET_LENGTH(CONVERT(name USING utf16)) BETWEEN 2 AND 200 AND ASCII(LEFT(name, 1)) > 32 AND ASCII(RIGHT(name, 1)) > 32);
ALTER TABLE product ADD CONSTRAINT ck_product_price CHECK (price BETWEEN 1 AND 1000000000);
ALTER TABLE stock ADD CONSTRAINT ck_stock_quantity CHECK (quantity >= 0);
