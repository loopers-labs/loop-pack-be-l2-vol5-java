-- Actual Hibernate SQL with its captured BOOLEAN/INTEGER/BIGINT bindings substituted.
-- Captured with COUNT TTL 0s; no SQL is executed by creating this file.

-- latest list
select pje1_0.id,pje1_0.name,pje1_0.price,bje1_0.id,bje1_0.name,pje1_0.like_count,pje1_0.description,pje1_0.stock,pje1_0.created_at from products pje1_0 join brands bje1_0 on bje1_0.id=pje1_0.brand_id where pje1_0.deleted=FALSE and bje1_0.deleted=FALSE order by pje1_0.created_at desc,pje1_0.id desc limit 0,20;

-- latest count
select count(pje1_0.id) from products pje1_0 where pje1_0.deleted=FALSE;

-- price list
select pje1_0.id,pje1_0.name,pje1_0.price,bje1_0.id,bje1_0.name,pje1_0.like_count,pje1_0.description,pje1_0.stock,pje1_0.created_at from products pje1_0 join brands bje1_0 on bje1_0.id=pje1_0.brand_id where pje1_0.deleted=FALSE and bje1_0.deleted=FALSE order by pje1_0.price,pje1_0.id desc limit 0,20;

-- price count
select count(pje1_0.id) from products pje1_0 where pje1_0.deleted=FALSE;

-- brand7 list
select pje1_0.id,pje1_0.name,pje1_0.price,bje1_0.id,bje1_0.name,pje1_0.like_count,pje1_0.description,pje1_0.stock,pje1_0.created_at from products pje1_0 join brands bje1_0 on bje1_0.id=pje1_0.brand_id where pje1_0.deleted=FALSE and bje1_0.deleted=FALSE and pje1_0.brand_id=7 order by pje1_0.price,pje1_0.id desc limit 0,20;

-- brand7 count
select count(pje1_0.id) from products pje1_0 where pje1_0.deleted=FALSE and pje1_0.brand_id=7;
