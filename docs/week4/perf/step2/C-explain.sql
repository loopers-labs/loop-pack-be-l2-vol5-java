-- C (브랜드 필터 없음, price_asc, page 0, size 20) 1회 호출에서 캡처한 SQL 의 실행 계획 (step2 에서만 기록, 전후 비교 아님. root 로 실행)
-- 브랜드 조회 13개 중 첫 번째(id=2)만 대표로 넣는다. EXPLAIN ANALYZE 는 쿼리를 실제로 실행한다.

-- 1
EXPLAIN
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null order by p1_0.amount,p1_0.id desc limit 0,20\G

EXPLAIN ANALYZE
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null order by p1_0.amount,p1_0.id desc limit 0,20\G

-- 2
EXPLAIN
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (2703,5406,8109,1,2704,5407,8110,2,2705,5408,8111,3,2706,5409,8112,4,2707,5410,8113,5) group by l1_0.product_id\G

EXPLAIN ANALYZE
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (2703,5406,8109,1,2704,5407,8110,2,2705,5408,8111,3,2706,5409,8112,4,2707,5410,8113,5) group by l1_0.product_id\G

-- 3
EXPLAIN
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=2\G

EXPLAIN ANALYZE
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=2\G
