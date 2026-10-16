-- A (brandId=7, price_asc, page 0, size 20) 1회 호출에서 캡처한 실제 SQL 3개의 실행 계획 (root 로 실행)
-- EXPLAIN ANALYZE 는 쿼리를 실제로 실행한다. root 커넥션이므로 application 카운터에는 잡히지 않는다.

-- 1. 상품 목록 (ProductRepositoryImpl.findAll)
EXPLAIN
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null and p1_0.brand_id=7 order by p1_0.amount,p1_0.id desc limit 0,20\G

EXPLAIN ANALYZE
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null and p1_0.brand_id=7 order by p1_0.amount,p1_0.id desc limit 0,20\G

-- 2. 좋아요 집계 (LikeJpaRepository.countGroupedByProductId)
EXPLAIN
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (5406,2706,6,8206,5506,2806,106,8306,5606,2906,206,8406,5706,3006,306,8506,5806,3106,406,8606) group by l1_0.product_id\G

EXPLAIN ANALYZE
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (5406,2706,6,8206,5506,2806,106,8306,5606,2906,206,8406,5706,3006,306,8506,5806,3106,406,8606) group by l1_0.product_id\G

-- 3. 브랜드 조회 (ProductFacade.findBrandNames → BrandJpaRepository.findById)
EXPLAIN
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=7\G

EXPLAIN ANALYZE
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=7\G
