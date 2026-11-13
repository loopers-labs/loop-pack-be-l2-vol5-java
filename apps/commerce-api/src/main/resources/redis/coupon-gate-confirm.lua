-- DB 결과 확인. 기존 쿠폰을 반환한 실행은 예약 차감만 한 번 되돌린다.
-- ARGV: userId, reservation, created(1/0)
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then return 0 end
if ARGV[3] ~= '0' and ARGV[3] ~= '1' then return redis.error_reply('invalid coupon result') end
if ARGV[3] == '0' then redis.call('HINCRBY', KEYS[1], 'remaining', 1) end
redis.call('HSET', KEYS[2], ARGV[1], 'A')
redis.call('HINCRBY', KEYS[1], 'pending', -1)
return 1
