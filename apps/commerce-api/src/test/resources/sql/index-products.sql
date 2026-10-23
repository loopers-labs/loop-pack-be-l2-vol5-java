-- products 인덱스 변경. Product 엔티티의 @Table(indexes)와 같은 결과이며, ddl-auto: none 환경(운영 등)에 수동으로 적용할 때 쓴다.
--
-- 고객 상품 목록의 브랜드 필터 + price_asc(where brand_id = ? and deleted_at is null order by price, id desc)를
-- 인덱스 순서로 처리하고 count 를 커버링으로 만든다. id 는 정렬이 내림차순이라 DESC 로 둔다(MySQL 8 이상).
-- 기존 idx_products_brand_id 는 새 인덱스의 왼쪽 접두(brand_id)가 대신하므로 지운다.
--
-- 주의: 브랜드 삭제의 FOR UPDATE 잠금 범위가 달라진다. 삭제 중 대기하는 이웃 브랜드가
-- 직전 브랜드에서 다음 브랜드로 바뀐다(영향 크기는 같다). LockBehaviorCharacterizationTest 가 이를 고정한다.

CREATE INDEX idx_products_brand_price_id ON products (brand_id, deleted_at, price, id DESC);
DROP INDEX idx_products_brand_id ON products;

-- 되돌리기
-- CREATE INDEX idx_products_brand_id ON products (brand_id);
-- DROP INDEX idx_products_brand_price_id ON products;
