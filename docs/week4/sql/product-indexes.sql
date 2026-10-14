-- W4 상품 목록 인덱스. 로컬 DB(ddl-auto: none)와 운영에는 이 SQL로 적용한다.
-- 테스트 DB는 ProductModel의 @Index로 같은 인덱스가 만들어진다.
-- 주의: seed를 다시 넣으려고 ddl-auto=create로 띄우면 테이블이 다시 만들어진다 — @Index에 있는 것만 남는다.

-- 고객 목록: WHERE brand_id = ? AND deleted_at IS NULL ORDER BY price ASC, id DESC LIMIT ?
-- = 조건 두 열(brand_id, deleted_at) 다음에 정렬 열(price, id DESC)을 같은 순서·방향으로 둔다.
CREATE INDEX idx_products_brand_deleted_price_id
    ON products (brand_id, deleted_at, price, id DESC);

-- 되돌리기
-- DROP INDEX idx_products_brand_deleted_price_id ON products;
