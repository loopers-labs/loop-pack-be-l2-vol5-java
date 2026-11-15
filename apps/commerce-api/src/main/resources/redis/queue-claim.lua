-- KEYS: waiting set, request hash, processing ownership hash
-- ARGV: unique execution owner
local function has_type(key, expected)
    local actual = redis.call('TYPE', key).ok
    return actual == 'none' or actual == expected
end
if not has_type(KEYS[1], 'set') or not has_type(KEYS[2], 'hash') or not has_type(KEYS[3], 'hash') then
    return redis.error_reply('invalid coupon queue data type')
end
local request_id = redis.call('SRANDMEMBER', KEYS[1])
if not request_id then
    return ''
end
local raw = redis.call('HGET', KEYS[2], request_id)
if not raw then
    return redis.error_reply('waiting coupon request is missing')
end
local request = cjson.decode(raw)
if request.status ~= 'WAITING' or redis.call('HEXISTS', KEYS[3], request_id) == 1 then
    return redis.error_reply('waiting coupon request is inconsistent')
end
local count = tonumber(request.attemptCount or 0)
local next_ms = tonumber(request.nextRetryAtMs or 0)
if not count or count < 0 or count > 4 or count % 1 ~= 0 or not next_ms or next_ms < 0 then
    return redis.error_reply('invalid coupon retry state')
end
local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
request.status = 'PROCESSING'
request.owner = ARGV[1]
redis.call('HSET', KEYS[2], request_id, cjson.encode(request))
redis.call('HSET', KEYS[3], request_id, ARGV[1])
redis.call('SREM', KEYS[1], request_id)
return request_id .. '|' .. request.campaignId .. '|' .. request.userId
        .. '|' .. tostring(count)
        .. '|' .. string.format('%.0f', next_ms)
        .. '|' .. (request.retryExhausted == true and '1' or '0')
        .. '|' .. (request.attemptPending == true and '1' or '0')
        .. '|' .. string.format('%.0f', now_ms)
