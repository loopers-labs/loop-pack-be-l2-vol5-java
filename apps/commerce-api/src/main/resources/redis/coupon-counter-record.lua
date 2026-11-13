-- Even a missing/invalid aggregate must fence an older delayed initialization.
redis.call('SET', KEYS[2], ARGV[2])
if redis.call('SISMEMBER', KEYS[1], '__ready__') ~= 1 then
    return -1
end
return redis.call('SADD', KEYS[1], ARGV[1])
