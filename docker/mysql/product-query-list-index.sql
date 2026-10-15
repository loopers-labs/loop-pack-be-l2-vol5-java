-- 같은 seed와 조회 SQL에서 아래 구문을 하나씩 실행한다.
-- 각 인덱스 상태에서 실행계획과 k6를 측정한 뒤 다음 구문으로 진행한다.
-- 시작 상태는 상품 PK·기존 brand_id 인덱스만 있고, 좋아요 조회 인덱스는 유지한 2단계 상태다.

-- 3단계: price 단일 인덱스 실험.
ALTER TABLE loopers.products
    ADD INDEX idx_products_price (price);

-- 3단계 측정 후 이번 실험에서 생성한 단일 인덱스만 제거한다.
ALTER TABLE loopers.products
    DROP INDEX idx_products_price;

-- 4단계: 브랜드·삭제 조건과 가격 정렬을 위한 복합 인덱스.
ALTER TABLE loopers.products
    ADD INDEX idx_products_brand_deleted_price (brand_id, deleted_at, price);

-- 적용 후 기존 FK 인덱스의 유지 여부도 확인한다.
-- MySQL이 자동 FK 인덱스를 대체한 경우에만 아래 주석의 복원 SQL을 개별 실행한다.
-- 원래 FK 인덱스를 복원한 뒤 이번에 추가한 복합 인덱스만 재생성한다.
-- ALTER TABLE loopers.products ADD INDEX FKa3a4mpsfdf4d2y6r8ra3sc8mv (brand_id);
-- ALTER TABLE loopers.products DROP INDEX idx_products_brand_deleted_price;
-- ALTER TABLE loopers.products ADD INDEX idx_products_brand_deleted_price (brand_id, deleted_at, price);
