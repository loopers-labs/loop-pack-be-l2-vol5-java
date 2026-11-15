-- KEYS: waiting set, request hash, campaign+user index, campaign status hash, processing ownership hash
-- ARGV: campaign id, user id, request id, waiting capacity, local worker accepts new requests
local function has_type(key, expected)
    local actual = redis.call('TYPE', key).ok
    return actual == 'none' or actual == expected
end
if not has_type(KEYS[1], 'set') or not has_type(KEYS[2], 'hash') or not has_type(KEYS[3], 'hash')
        or not has_type(KEYS[4], 'hash') or not has_type(KEYS[5], 'hash') then
    return redis.error_reply('invalid coupon queue data type')
end
local user_key = ARGV[1] .. ':' .. ARGV[2]
local existing = redis.call('HGET', KEYS[3], user_key)
if existing then
    local raw = redis.call('HGET', KEYS[2], existing)
    if not raw then
        return redis.error_reply('coupon request index is inconsistent')
    end
    local request = cjson.decode(raw)
    if request.campaignId ~= ARGV[1] or request.userId ~= ARGV[2] then
        return redis.error_reply('coupon request index is inconsistent')
    end
    if request.status == 'WAITING' then
        if redis.call('SISMEMBER', KEYS[1], existing) ~= 1 then
            return redis.error_reply('waiting coupon request is inconsistent')
        end
        return 'EXISTING:' .. existing
    end
    if request.status == 'PROCESSING' then
        if type(request.owner) ~= 'string' or request.owner == ''
                or redis.call('HGET', KEYS[5], existing) ~= request.owner then
            return redis.error_reply('processing coupon request owner is inconsistent')
        end
        return 'EXISTING:' .. existing
    end
    -- Terminal records from an older queue version require an explicit migration.
    return redis.error_reply('unsupported coupon request state')
end
if ARGV[5] ~= '1' then
    return 'PAUSED'
end
if redis.call('HGET', KEYS[4], ARGV[1]) == 'SOLD_OUT' then
    return 'SOLD_OUT'
end
local capacity = tonumber(ARGV[4])
if not capacity or capacity < 1 then
    return redis.error_reply('invalid coupon queue capacity')
end
if redis.call('SCARD', KEYS[1]) >= capacity then
    return 'FULL'
end
local record = cjson.encode({
    campaignId = ARGV[1], userId = ARGV[2], status = 'WAITING', owner = ''
})
redis.call('HSET', KEYS[2], ARGV[3], record)
redis.call('HSET', KEYS[3], user_key, ARGV[3])
redis.call('SADD', KEYS[1], ARGV[3])
return 'ACCEPTED:' .. ARGV[3]
