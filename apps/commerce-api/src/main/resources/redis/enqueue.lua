-- KEYS[1] = queue:{campaignId}
-- KEYS[2] = queue_hb:{campaignId}
-- ARGV[1] = userId
-- ARGV[2] = now (Unix epoch seconds)
-- ARGV[3] = maxQueueSize
-- Returns: "ENQUEUED" | "ALREADY_QUEUED" | "QUEUE_FULL"

local queueKey = KEYS[1]
local hbKey    = KEYS[2]
local userId   = ARGV[1]
local now      = tonumber(ARGV[2])
local maxSize  = tonumber(ARGV[3])

if redis.call('ZSCORE', queueKey, userId) then
    return "ALREADY_QUEUED"
end

if redis.call('ZCARD', queueKey) >= maxSize then
    return "QUEUE_FULL"
end

redis.call('ZADD', queueKey, now, userId)
redis.call('ZADD', hbKey, now, userId)
return "ENQUEUED"
