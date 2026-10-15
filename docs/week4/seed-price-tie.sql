-- ============================================================
-- 가격 동점 검증용 소규모 픽스처
-- price_asc 정렬 시 동점이면 id desc(최신 등록 우선)가 적용되는지 확인
-- seed.sql 실행 후 별도로 실행합니다.
-- ============================================================

-- 동일 가격(50,000원) 상품 5개 — brand_id=1 사용
INSERT INTO products (brand_id, name, price, stock, created_at, updated_at, deleted_at)
VALUES
    (1, '동점_A', 50000, 10, NOW(), NOW(), NULL),
    (1, '동점_B', 50000, 10, NOW(), NOW(), NULL),
    (1, '동점_C', 50000, 10, NOW(), NOW(), NULL),
    (1, '동점_D', 50000, 10, NOW(), NOW(), NULL),
    (1, '동점_E', 50000, 10, NOW(), NOW(), NULL);

-- 검증: price=50000인 상품은 id 내림차순 (동점_E > D > C > B > A)
SELECT id, name, price
FROM products
WHERE price = 50000 AND deleted_at IS NULL
ORDER BY price ASC, id DESC;
