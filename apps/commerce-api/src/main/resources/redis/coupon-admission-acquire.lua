local capacity = tonumber(ARGV[1])
local token = ARGV[2]

if not capacity or capacity < 1 or capacity % 1 ~= 0 or not token or token == '' then
    return redis.error_reply('invalid coupon admission input')
end

if redis.call('SISMEMBER', KEYS[1], token) == 1 then
    return 1
end

if redis.call('SCARD', KEYS[1]) >= capacity then
    return 0
end

redis.call('SADD', KEYS[1], token)
return 1
