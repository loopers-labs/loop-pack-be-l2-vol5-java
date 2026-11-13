-- 8주차 4단계 측정 준비(D136). local DB 전용이다. 운영·테스트 DB에서 실행하지 않는다.
-- 측정할 때마다(전·후 각각) 앱을 띄운 뒤, 부하를 주기 직전에 실행한다. 다시 실행해도 같은 시작 상태가 된다.
--   docker exec -i <mysql 컨테이너> mysql -uapplication -papplication loopers < scripts/perf/week8/prepare.sql
-- Redis의 수용 자리 키는 이 SQL로 지울 수 없다. 측정 전에 따로 지운다:
--   docker exec <redis 컨테이너> sh -c "redis-cli --scan --pattern 'coupon:issue:permits:*' | xargs -r redis-cli del"

-- 사용자 1..1000. CustomerIdentity가 X-USER-ID의 사용자가 있는지 확인한다.
SET SESSION cte_max_recursion_depth = 10000;
INSERT IGNORE INTO users (id)
WITH RECURSIVE seq (n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT n FROM seq;

-- 측정 캠페인 9001: 총량 100, 활성, 발급 수 0. 이전 측정에서 발급된 쿠폰은 지운다(이 캠페인 것만).
DELETE FROM issued_coupon WHERE campaign_id = 9001;
INSERT INTO coupon_campaign (id, name, active, total_quota, issued_count, discount_amount, created_at)
VALUES (9001, '측정용 한정 수량 쿠폰', TRUE, 100, 0, 3000, NOW(6))
ON DUPLICATE KEY UPDATE active = TRUE, total_quota = 100, issued_count = 0;

-- 수용 설정: 자리 3, 열림. epoch는 그대로 둔다(Redis 키를 위에서 지웠다는 전제).
INSERT INTO coupon_admission_settings (id, capacity, open, epoch, updated_at)
VALUES (1, 3, TRUE, 1, NOW(6))
ON DUPLICATE KEY UPDATE capacity = 3, open = TRUE, closed_reason = NULL, closed_at = NULL, updated_at = NOW(6);

-- 측정 뒤 확인용: 실제 쿠폰 수와 issued_count
-- SELECT c.issued_count, (SELECT COUNT(*) FROM issued_coupon i WHERE i.campaign_id = c.id) AS coupons
-- FROM coupon_campaign c WHERE c.id = 9001;
