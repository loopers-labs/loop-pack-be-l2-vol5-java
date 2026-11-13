-- 발급 commit 뒤 Redis 갱신과 XACK 를 한 번에 한다 (8주차 D-125). DB 가 원본이고 이 갱신은 사본을 맞추는 일이라 반복해도 같다.
-- ISSUED · FAILED 는 끝 상태라 덮지 않는다. 늦게 끝난 이전 worker 가 다른 결과를 써도 한 방향만 유지한다 (D-126)
-- KEYS: 1 users, 2 stream
-- ARGV: 1 userId, 2 sequence, 3 couponId, 4 group, 5 entryId
local stored = redis.call('HGET', KEYS[1], ARGV[1])
local state = stored and string.match(stored, '^([A-Z_]+):') or nil
if state ~= 'ISSUED' and state ~= 'FAILED' then
  redis.call('HSET', KEYS[1], ARGV[1], 'ISSUED:' .. ARGV[2] .. ':' .. ARGV[3])
end
return redis.call('XACK', KEYS[2], ARGV[4], ARGV[5])
