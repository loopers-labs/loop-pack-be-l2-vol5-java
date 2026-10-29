-- E-6·전체 흐름 결과 확인. 실행: docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6 < docs/week6/k6/result.sql
SELECT status, COUNT(*) AS orders FROM orders GROUP BY status;
SELECT status, COUNT(*) AS payments FROM payments GROUP BY status;
SELECT
  COUNT(*)                                   AS outbox_rows,
  SUM(published_at IS NOT NULL)              AS published,
  SUM(published_at IS NULL AND last_error IS NULL)     AS unpublished_no_error,
  SUM(published_at IS NULL AND last_error IS NOT NULL) AS unpublished_with_error
FROM event_outbox;
SELECT LEFT(last_error, 120) AS last_error, COUNT(*) AS rows_ FROM event_outbox WHERE last_error IS NOT NULL GROUP BY LEFT(last_error, 120);
SELECT COUNT(*) AS consumed FROM consumed_order_confirmation;
SELECT COUNT(*) AS activity_rows FROM product_purchase_activity;
