-- 실제 HTTP 진단 요청에서 확인한 SQL과 숫자 바인딩 값
USE loopers;

select pje1_0.id,pje1_0.brand_id,pje1_0.created_at,pje1_0.deleted_at,pje1_0.name,pje1_0.price,pje1_0.stock,pje1_0.updated_at from products pje1_0 left join brands b1_0 on b1_0.id=pje1_0.brand_id where pje1_0.deleted_at is null and b1_0.id=7 order by pje1_0.price,pje1_0.id limit 20;

select bje1_0.id,bje1_0.created_at,bje1_0.deleted_at,bje1_0.name,bje1_0.updated_at from brands bje1_0 where bje1_0.id in (7);

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=5406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=105406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=205406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=305406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=405406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=505406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=605406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=705406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=805406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=905406;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=2706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=102706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=202706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=302706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=402706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=502706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=602706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=702706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=802706;

select count(lje1_0.id) from likes lje1_0 where lje1_0.product_id=902706;
