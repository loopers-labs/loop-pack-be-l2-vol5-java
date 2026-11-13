-- KEYS: request hash, processing ownership hash
-- ARGV: request id, unique owner
-- Record only a confirmed rollback. Attempt count was already reserved before DB access.
local function has_type(key, expected)
    local actual = redis.call('TYPE', key).ok
    return actual == 'none' or actual == expected
end
if not has_type(KEYS[1], 'hash') or not has_type(KEYS[2], 'hash') then
    return redis.error_reply('invalid coupon queue data type')
end
local raw = redis.call('HGET', KEYS[1], ARGV[1])
if not raw then
    return redis.error_reply('coupon request is missing')
end
local request = cjson.decode(raw)
if request.status ~= 'PROCESSING' or request.owner ~= ARGV[2]
        or redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
    return redis.error_reply('coupon request owner is inconsistent')
end
local count = tonumber(request.attemptCount or 0)
if not count or count < 1 or count > 4 or count % 1 ~= 0 or request.attemptPending ~= true then
    return redis.error_reply('coupon attempt was not reserved')
end
local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
request.attemptPending = false
if count == 4 then
    request.retryExhausted = true
    request.nextRetryAtMs = 0
    redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(request))
    return tostring(count) .. '|0|1|' .. string.format('%.0f', now_ms)
end
local delay_ms = 1000 * (2 ^ (count - 1))
local next_ms = now_ms + delay_ms
request.nextRetryAtMs = next_ms
redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(request))
return tostring(count) .. '|' .. string.format('%.0f', next_ms) .. '|0|' .. string.format('%.0f', now_ms)
