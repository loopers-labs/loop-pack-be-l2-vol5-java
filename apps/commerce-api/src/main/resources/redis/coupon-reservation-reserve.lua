if redis.call('EXISTS', KEYS[2]) == 1 then
  return {-3, 0}
end
local existing = redis.call('ZSCORE', KEYS[1], ARGV[1])
if existing then
  return {0, tonumber(existing)}
end
local size = redis.call('ZCARD', KEYS[1])
if size < tonumber(ARGV[3]) then
  return {-2, 0}
end
if size >= tonumber(ARGV[2]) then
  return {-1, 0}
end
local top = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local next = 1
if top[2] then
  next = tonumber(top[2]) + 1
end
redis.call('ZADD', KEYS[1], next, ARGV[1])
return {1, next}
