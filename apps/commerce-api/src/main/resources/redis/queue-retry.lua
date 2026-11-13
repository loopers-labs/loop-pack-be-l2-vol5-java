-- KEYS: waiting set, request hash, processing ownership hash
-- ARGV: request id, unique owner, waiting capacity
local function has_type(key, expected)
    local actual = redis.call('TYPE', key).ok
    return actual == 'none' or actual == expected
end
if not has_type(KEYS[1], 'set') or not has_type(KEYS[2], 'hash') or not has_type(KEYS[3], 'hash') then
    return redis.error_reply('invalid coupon queue data type')
end
local raw = redis.call('HGET', KEYS[2], ARGV[1])
if not raw then
    return -1
end
local request = cjson.decode(raw)
if request.owner ~= ARGV[2] then
    return -1
end
-- A retry whose response was lost can be repeated without enqueuing twice.
if request.status == 'WAITING' then
    if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1
            and redis.call('HEXISTS', KEYS[3], ARGV[1]) == 0 then
        return 1
    end
    return -1
end
if request.status ~= 'PROCESSING' or redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then
    return -1
end
if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[3]) then
    return 0
end
request.status = 'WAITING'
-- This script is an operator action. The caller must first reconcile the
-- prior execution and DB outcome; do not call merely because time elapsed.
if request.attemptPending == true then
    local count = tonumber(request.attemptCount or 0)
    local previous = tonumber(request.nextRetryAtMs or 0)
    if not count or count < 1 or count > 4 or count % 1 ~= 0 or not previous or previous < 0 then
        return redis.error_reply('invalid coupon retry state')
    end
    if count < 4 then
        -- An interrupted reservation may have reached a confirmed rollback
        -- before it could record a delay. Conservatively wait in either case.
        local now = redis.call('TIME')
        local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        local delay_ms = 1000 * (2 ^ (count - 1))
        request.nextRetryAtMs = math.max(previous, now_ms + delay_ms)
    end
end
request.attemptPending = false
redis.call('HSET', KEYS[2], ARGV[1], cjson.encode(request))
redis.call('SADD', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
return 1
