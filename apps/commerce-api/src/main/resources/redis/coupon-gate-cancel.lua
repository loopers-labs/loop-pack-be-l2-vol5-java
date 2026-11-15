-- 통과를 되돌린다. 그 실행이 남긴 확인 전 기록이 그대로일 때만 지우고 남은 수를 올려, 두 번 불려도 한 번만 반영한다.
-- DB 발급이 확인된 기록("A")이나 다른 실행이 이어받은 기록은 건드리지 않는다.
-- KEYS[1] = 판단 정보(hash), KEYS[2] = 사용자 상태(hash), ARGV[1] = 사용자 ID, ARGV[2] = 그 실행의 확인 전 기록 값
if redis.call('EXISTS', KEYS[1]) == 0 then
  return 0
end
local remaining = tonumber(redis.call('HGET', KEYS[1], 'remaining'))
if not remaining or remaining % 1 ~= 0 then return redis.error_reply('invalid coupon stock') end
if redis.call('HGET', KEYS[2], ARGV[1]) == ARGV[2] then
  redis.call('HDEL', KEYS[2], ARGV[1])
  redis.call('HINCRBY', KEYS[1], 'remaining', 1)
  redis.call('HINCRBY', KEYS[1], 'pending', -1)
  return 1
end
return 0
