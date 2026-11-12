-- KEYS[1] = active:{campaignId}
-- ARGV[1] = userId
-- Returns: 1 (always succeeds, idempotent)

redis.call('SREM', KEYS[1], ARGV[1])
return 1
