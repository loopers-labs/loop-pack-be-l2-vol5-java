if redis.call('SISMEMBER', KEYS[1], '__ready__') ~= 1 then
    return -1
end
return redis.call('SCARD', KEYS[1]) - 1
