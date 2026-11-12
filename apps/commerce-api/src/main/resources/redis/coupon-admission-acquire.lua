-- KEYS[1] = permits 키
-- ARGV[1] = 유효 용량 (C - 보정값), ARGV[2] = 실행 token
-- 반환: 1 = 획득(또는 같은 token이 이미 있음), 0 = 자리 없음
local capacity = tonumber(ARGV[1])
local token = ARGV[2]
if not capacity or capacity < 1 or capacity % 1 ~= 0
   or not token or token == '' then
  return redis.error_reply('invalid admission input')
end
if redis.call('SISMEMBER', KEYS[1], token) == 1 then return 1 end
if redis.call('SCARD', KEYS[1]) >= capacity then return 0 end
redis.call('SADD', KEYS[1], token)
return 1
