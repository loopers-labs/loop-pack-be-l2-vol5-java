-- KEYS: request hash, processing ownership hash
-- ARGV: request id, unique owner
-- Each execution is recorded before DB access, so a crashed worker cannot
-- silently reset the attempt limit. A pending attempt needs reconciliation.
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
local next_ms = tonumber(request.nextRetryAtMs or 0)
if not count or count < 0 or count > 4 or count % 1 ~= 0 or not next_ms or next_ms < 0 then
    return redis.error_reply('invalid coupon retry state')
end
local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local function result(status, at)
    return status .. '|' .. tostring(count) .. '|' .. string.format('%.0f', at)
            .. '|' .. string.format('%.0f', now_ms)
end
if request.retryExhausted == true then
    return result('EXHAUSTED', next_ms)
end
if request.attemptPending == true then
    return result('PENDING', next_ms)
end
if count >= 4 then
    return redis.error_reply('coupon retry limit state is inconsistent')
end
if next_ms > now_ms then
    return result('WAIT', next_ms)
end
count = count + 1
request.attemptCount = count
request.attemptPending = true
request.nextRetryAtMs = 0
redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(request))
return result('RESERVED', 0)
