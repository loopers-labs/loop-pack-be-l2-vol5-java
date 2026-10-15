-- 측정 대상 쿼리. 현재 앱(ProductPersistenceAdapter.search·findLikeCounts)의 실제 SQL과 같은 형태를 쓴다.
-- 설계 18절 이후 Q6~Q8은 like_count 정렬, Q9·Q10은 products.like_count 조회로 바뀌었다.
-- 개선 전 SQL(종속 서브쿼리·product_likes 집계)은 baseline 원본 기록(query-performance-raw.md)에 남아 있다.
-- 이름과 스냅샷 형식(product_id·total)을 유지해 compare로 개선 전후 결과를 비교한다.
-- 형식: '-- @name 이름', '-- @desc 설명', 선택 '-- @snapshot 형식' 다음 줄에 한 줄짜리 SELECT 문 (세미콜론으로 끝냄)
-- 스냅샷 형식: list(기본, id·price·created_at) / list_likes(+ 좋아요 수) / agg(product_id·total)
-- 브랜드 필터 값: 1 = 상품이 가장 많은 브랜드(미삭제 약 6,700개), 200 = 상품이 적은 브랜드(미삭제 약 230개)

-- @name Q1_latest_all
-- @desc 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q2_latest_brand
-- @desc 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q3_price_all
-- @desc 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q4_price_brand
-- @desc 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q5_latest_deep_page
-- @desc 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- @name Q6_likes_all
-- @snapshot list_likes
-- @desc 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q7_likes_brand
-- @snapshot list_likes
-- @desc 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q8_likes_small_brand
-- @snapshot list_likes
-- @desc 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- @name Q9_like_count_page
-- @snapshot agg
-- @desc 목록 1페이지 상품 20개의 좋아요 수 일괄 조회 (findLikeCounts)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);

-- @name Q10_like_count_detail
-- @snapshot agg
-- @desc 상품 상세의 좋아요 수 (인기 상품 1)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1);
