-- 값과 남은 수명을 같은 시점으로 읽으려고 GET 과 PTTL 을 한 번에 실행한다. TTL 없는 키는 MISS 다.
if #KEYS ~= 1 or #ARGV ~= 0 then
  return redis.error_reply('COUPON_CACHE_ARGS read expects 1 key and 0 args')
end
local key_type = redis.call('TYPE', KEYS[1])['ok']
if key_type == 'none' then
  return {'MISS', '', ''}
end
if key_type ~= 'string' then
  return redis.error_reply('COUPON_CACHE_TYPE ' .. tostring(key_type))
end
local value = redis.call('GET', KEYS[1])
local remaining = redis.call('PTTL', KEYS[1])
if not value or remaining <= 0 then
  return {'MISS', '', ''}
end
return {'HIT', value, string.format('%.0f', remaining)}
