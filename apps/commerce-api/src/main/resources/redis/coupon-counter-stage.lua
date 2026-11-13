if redis.call('EXISTS', KEYS[1]) ~= 0 then
    return -1
end
redis.call('SET', KEYS[2], ARGV[1])
redis.call('SADD', KEYS[1], '__ready__')
redis.call('EXPIRE', KEYS[1], 60)
return 1
