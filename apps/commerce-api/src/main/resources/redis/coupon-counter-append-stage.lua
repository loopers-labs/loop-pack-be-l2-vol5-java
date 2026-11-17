if redis.call('SISMEMBER', KEYS[1], '__ready__') ~= 1 then
    return -1
end
for i = 1, #ARGV do
    redis.call('SADD', KEYS[1], ARGV[i])
end
redis.call('EXPIRE', KEYS[1], 60)
return 1
