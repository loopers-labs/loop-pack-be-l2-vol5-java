-- 쿠폰 캐시 단일 키 읽기(기발급·품절 공용). KEYS = 캐시 키 1개, ARGV 없음. 반환 [HIT|MISS, 값, 남은 밀리초].
-- GET 과 PTTL 을 한 번에 읽어 값과 남은 수명이 같은 시점의 것이 되게 한다. 쓰기·기한 연장·삭제는 하지 않는다.
-- TTL 없는 키(PTTL -1)와 만료 직전 키도 MISS 다. 문자열이 아닌 키는 설명할 수 없으므로 오류로 올린다.
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
-- 큰 수도 지수 표기 없이 십진 문자열로 보낸다.
return {'HIT', value, string.format('%.0f', remaining)}
