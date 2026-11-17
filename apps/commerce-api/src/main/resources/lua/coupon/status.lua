-- 내 상태 조회 (D-4-5). 따로 부르면 EXISTS와 ZRANK 사이에 스케줄러가 이 사람을 입장시켜 "줄에 없음"이 되므로 한 스크립트로 본다.
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set  KEYS[3] 줄  KEYS[4] 이 사용자의 입장 키
-- ARGV[1] userId  ARGV[2] 당첨자 수
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'UNKNOWN', -1}
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return {'ISSUED', -1}
end
if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[2]) then
  return {'SOLD_OUT', -1}
end
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {'ADMITTED', -1}
end
local rank = redis.call('ZRANK', KEYS[3], ARGV[1])
if rank then
  return {'WAITING', rank}
end
return {'NOT_IN_QUEUE', -1}
