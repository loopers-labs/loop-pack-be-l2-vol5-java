-- 작업 큐의 확정 취소. 그 작업의 예약(R)이 그대로일 때만 취소 표시(C)로 바꾸고 남은 수를 돌린다.
-- C는 남겨 두어 같은 사용자의 재신청을 받지 않는다. 이미 C·A이거나 다른 예약이면 아무것도 바꾸지 않아 여러 번 불려도 한 번만 반영된다.
-- KEYS[1] = stock(hash), KEYS[2] = users(hash). ARGV[1] = 사용자 ID, ARGV[2] = 그 작업의 예약 값
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then return 0 end
redis.call('HSET', KEYS[2], ARGV[1], 'C')
redis.call('HINCRBY', KEYS[1], 'remaining', 1)
redis.call('HINCRBY', KEYS[1], 'pending', -1)
return 1
