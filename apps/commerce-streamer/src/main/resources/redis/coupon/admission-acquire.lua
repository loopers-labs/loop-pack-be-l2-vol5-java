-- 세마포어 획득: 같은 token 확인 · 현재 자리 수 확인 · 빈자리 추가를 한 번에 한다 (8주차 D-157, 과제 3장)
-- SCARD 와 SADD 를 두 번의 왕복으로 나누면 마지막 자리를 둘이 함께 차지할 수 있다
-- KEYS: 1 token set
-- ARGV: 1 capacity, 2 token
-- 돌려주는 값: 1 수용, 0 자리 없음
local capacity = tonumber(ARGV[1])
local token = ARGV[2]
if not capacity or capacity < 1 or capacity % 1 ~= 0 or not token or token == '' then
  return redis.error_reply('invalid admission input')
end
if redis.call('SISMEMBER', KEYS[1], token) == 1 then
  return 1
end
if redis.call('SCARD', KEYS[1]) >= capacity then
  return 0
end
redis.call('SADD', KEYS[1], token)
return 1
