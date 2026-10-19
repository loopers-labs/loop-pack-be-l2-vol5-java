USE loopers;

SELECT 'product_page' AS query_label;

EXPLAIN ANALYZE select pje1_0.id,pje1_0.brand_id,pje1_0.created_at,pje1_0.deleted_at,pje1_0.name,pje1_0.price,pje1_0.stock,pje1_0.updated_at from products pje1_0 left join brands b1_0 on b1_0.id=pje1_0.brand_id where pje1_0.deleted_at is null and b1_0.id=7 order by pje1_0.price,pje1_0.id limit 20;

SELECT 'brand_batch' AS query_label;

EXPLAIN ANALYZE select bje1_0.id,bje1_0.created_at,bje1_0.deleted_at,bje1_0.name,bje1_0.updated_at from brands bje1_0 where bje1_0.id in (7);

SELECT 'like_count_5406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=5406;

SELECT 'like_count_105406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=105406;

SELECT 'like_count_205406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=205406;

SELECT 'like_count_305406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=305406;

SELECT 'like_count_405406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=405406;

SELECT 'like_count_505406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=505406;

SELECT 'like_count_605406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=605406;

SELECT 'like_count_705406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=705406;

SELECT 'like_count_805406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=805406;

SELECT 'like_count_905406' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=905406;

SELECT 'like_count_2706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=2706;

SELECT 'like_count_102706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=102706;

SELECT 'like_count_202706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=202706;

SELECT 'like_count_302706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=302706;

SELECT 'like_count_402706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=402706;

SELECT 'like_count_502706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=502706;

SELECT 'like_count_602706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=602706;

SELECT 'like_count_702706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=702706;

SELECT 'like_count_802706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=802706;

SELECT 'like_count_902706' AS query_label;

EXPLAIN ANALYZE select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=902706;
