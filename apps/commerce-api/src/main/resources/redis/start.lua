-- KEYS[1] = admitted:{campaignId}
-- KEYS[2] = active:{campaignId}
-- ARGV[1] = userId
-- ARGV[2] = now (Unix epoch seconds)
-- ARGV[3] = admitTtlSeconds
-- Returns: "STARTED" | "NOT_ADMITTED" | "EXPIRED"

local admittedKey = KEYS[1]
local activeKey   = KEYS[2]
local userId      = ARGV[1]
local now         = tonumber(ARGV[2])
local admitTtl    = tonumber(ARGV[3])

local score = redis.call('ZSCORE', admittedKey, userId)
if not score then
    return "NOT_ADMITTED"
end

score = tonumber(score)
if score <= (now - admitTtl) then
    redis.call('ZREM', admittedKey, userId)
    return "EXPIRED"
end

redis.call('ZREM', admittedKey, userId)
redis.call('SADD', activeKey, userId)
return "STARTED"
