-- 001~006 스키마가 있는 DB에 한 번 적용한다. 기존 인덱스와 데이터는 보존한다.
-- 브랜드·미삭제 필터 뒤 price ASC, id DESC 순서로 고객 목록을 읽는다.
CREATE INDEX `idx_product_brand_price`
    ON `product` (`brand_id`, `deleted_at`, `price` ASC, `id` DESC);
