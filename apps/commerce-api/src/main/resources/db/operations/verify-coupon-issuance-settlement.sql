-- Run on the primary, in the transaction holding coupon_campaign(@campaign_id) FOR UPDATE.
-- Exactly one eligible row and a successful COMMIT are required before retiring the Redis token.
SELECT r.status, COUNT(o.id) AS owned_count, MIN(o.id) AS coupon_id
FROM coupon_reservation r
LEFT JOIN owned_coupon o ON o.reservation_id = r.id
WHERE r.id = @reservation_id AND r.campaign_id = @campaign_id AND r.user_id = @user_id
GROUP BY r.id, r.status
HAVING (r.status = 'WAITING' AND COUNT(o.id) = 0)
    OR (r.status = 'ISSUED' AND COUNT(o.id) = 1);
