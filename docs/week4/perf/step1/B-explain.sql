-- B (brandId=7, likes_desc, page 0, size 20) 1회 호출에서 캡처한 실제 SQL 3개의 실행 계획 (step1, root 로 실행)
-- EXPLAIN ANALYZE 는 쿼리를 실제로 실행한다.

-- 1
EXPLAIN
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null and p1_0.brand_id=7 order by (select count(l1_0.id) from likes l1_0 where l1_0.product_id=p1_0.id) desc,p1_0.id desc limit 0,20\G

EXPLAIN ANALYZE
select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.amount,p1_0.quantity,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null and p1_0.brand_id=7 order by (select count(l1_0.id) from likes l1_0 where l1_0.product_id=p1_0.id) desc,p1_0.id desc limit 0,20\G

-- 2
EXPLAIN
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (9606,206,9906,5206,5506,806,5806,1106,6106,1406,6406,1706,6706,2006,7006,2306,7306,2606,7606,2906) group by l1_0.product_id\G

EXPLAIN ANALYZE
select l1_0.product_id,count(l1_0.id) from likes l1_0 where l1_0.product_id in (9606,206,9906,5206,5506,806,5806,1106,6106,1406,6406,1706,6706,2006,7006,2306,7306,2606,7606,2906) group by l1_0.product_id\G

-- 3
EXPLAIN
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=7\G

EXPLAIN ANALYZE
select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=7\G
