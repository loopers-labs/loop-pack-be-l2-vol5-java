-- A client timeout does not cancel a command already submitted to Redis.
-- Any later record or build invalidates this build's publication token.
if redis.call('GET', KEYS[3]) ~= ARGV[2] then
    return -1
end
if redis.call('SISMEMBER', KEYS[1], '__ready__') ~= 1 then
    return -1
end
if redis.call('SCARD', KEYS[1]) ~= tonumber(ARGV[1]) + 1 then
    return -1
end
redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('PERSIST', KEYS[2])
return 1
