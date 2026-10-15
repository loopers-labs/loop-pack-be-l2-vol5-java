-- 확장 데이터: 상품 100만, 브랜드 쏠림(brand 1 = 50%), 삭제 10%
DROP DATABASE IF EXISTS loopers_scale;
CREATE DATABASE loopers_scale;
USE loopers_scale;
CREATE TABLE product LIKE loopers.product;
CREATE TABLE likes LIKE loopers.likes;
CREATE TABLE d (n INT PRIMARY KEY); INSERT INTO d VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TABLE seq (n INT PRIMARY KEY);
INSERT INTO seq SELECT a.n+b.n*10+c.n*100+e.n*1000+f.n*10000+g.n*100000+1 FROM d a,d b,d c,d e,d f,d g;
SET @t=now(6);
INSERT INTO product (id,brand_id,name,price,stock_remaining,created_at,updated_at,deleted_at)
SELECT n,
  CASE WHEN n%2=0 THEN 1 ELSE (n%99)+2 END,
  concat('상품-', lpad(n,7,'0')),
  ((n*37)%100000)+100,
  100,
  timestamp('2026-01-01') + interval n second,
  timestamp('2026-01-01') + interval n second,
  CASE WHEN n%10=0 THEN timestamp('2026-06-01') ELSE NULL END
FROM seq;
SELECT 'load_1M_ms', timestampdiff(microsecond,@t,now(6))/1000;
ANALYZE TABLE product;
SELECT brand_id, count(*), sum(deleted_at is null) FROM product WHERE brand_id IN (1,7) GROUP BY brand_id;
