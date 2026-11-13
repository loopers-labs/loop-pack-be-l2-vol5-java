-- 실행 종료. 자기 token 만 반환하고, 저장했으면 couponId 를 기록한다.
-- KEYS = [winners, running], ARGV = [userId, token, couponId 또는 '']
redis.call('SREM', KEYS[2], ARGV[2])
local current = redis.call('HGET', KEYS[1], ARGV[1])
if ARGV[3] ~= '' and (current == 'PENDING' or current == 'TICKETED') then
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
end
return 'OK'
