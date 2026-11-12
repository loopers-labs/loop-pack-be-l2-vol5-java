-- KEYS[1] = queue:{campaignId}
-- KEYS[2] = admitted:{campaignId}
-- KEYS[3] = active:{campaignId}
-- KEYS[4] = queue_hb:{campaignId}
-- ARGV[1] = userId
-- ARGV[2] = now (Unix epoch seconds)
-- ARGV[3] = admitTtlSeconds
-- Returns: "ACTIVE" | "ADMITTED" | "QUEUED:{rank}" | "NONE"

local queueKey    = KEYS[1]
local admittedKey = KEYS[2]
local activeKey   = KEYS[3]
local hbKey       = KEYS[4]
local userId      = ARGV[1]
local now         = tonumber(ARGV[2])
local admitTtl    = tonumber(ARGV[3])

if redis.call('SISMEMBER', activeKey, userId) == 1 then
    return "ACTIVE"
end

local admitScore = redis.call('ZSCORE', admittedKey, userId)
if admitScore then
    admitScore = tonumber(admitScore)
    if admitScore >= (now - admitTtl) then
        return "ADMITTED"
    else
        redis.call('ZREM', admittedKey, userId)
    end
end

local queueScore = redis.call('ZSCORE', queueKey, userId)
if queueScore then
    redis.call('ZADD', hbKey, now, userId)
    local rank = redis.call('ZRANK', queueKey, userId)
    return "QUEUED:" .. (rank + 1)
end

return "NONE"
