-- hit 확인 쿼리 (A-hit-sql.txt 에서 캡처한 실제 SQL)
EXPLAIN select p1_0.id,p1_0.quantity from products p1_0 where p1_0.id in (5406,2706,6,8206,5506,2806,106,8306,5606,2906,206,8406,5706,3006,306,8506,5806,3106,406,8606) and p1_0.deleted_at is null;
EXPLAIN ANALYZE select p1_0.id,p1_0.quantity from products p1_0 where p1_0.id in (5406,2706,6,8206,5506,2806,106,8306,5606,2906,206,8406,5706,3006,306,8506,5806,3106,406,8606) and p1_0.deleted_at is null;
