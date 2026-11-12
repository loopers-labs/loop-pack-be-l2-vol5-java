-- KEYS[1] = queue:{campaignId}
-- KEYS[2] = queue_hb:{campaignId}
-- KEYS[3] = admitted:{campaignId}
-- KEYS[4] = active:{campaignId}
-- ARGV[1] = now (Unix epoch seconds)
-- ARGV[2] = capacity
-- ARGV[3] = heartbeatTimeoutSeconds
-- ARGV[4] = admitTtlSeconds
-- Returns: count of promoted users (integer)

local queueKey    = KEYS[1]
local hbKey       = KEYS[2]
local admittedKey = KEYS[3]
local activeKey   = KEYS[4]
local now         = tonumber(ARGV[1])
local capacity    = tonumber(ARGV[2])
local hbTimeout   = tonumber(ARGV[3])
local admitTtl    = tonumber(ARGV[4])

-- Remove dead heartbeats and corresponding queue entries
local deadline = now - hbTimeout
local deadUsers = redis.call('ZRANGEBYSCORE', hbKey, '-inf', deadline)
for _, uid in ipairs(deadUsers) do
    redis.call('ZREM', queueKey, uid)
    redis.call('ZREM', hbKey, uid)
end

-- Expire admitted entries whose TTL has passed
redis.call('ZREMRANGEBYSCORE', admittedKey, '-inf', now - admitTtl)

-- Free slots = capacity - admitted - active
local admitted = redis.call('ZCARD', admittedKey)
local active   = redis.call('SCARD', activeKey)
local free     = capacity - admitted - active

if free <= 0 then
    return 0
end

-- Promote the first `free` users from the queue (oldest first = lowest score)
local toPromote = redis.call('ZRANGEBYSCORE', queueKey, '-inf', '+inf', 'LIMIT', 0, free)
local count = 0
for _, uid in ipairs(toPromote) do
    redis.call('ZREM', queueKey, uid)
    redis.call('ZREM', hbKey, uid)
    redis.call('ZADD', admittedKey, now, uid)
    count = count + 1
end

return count
