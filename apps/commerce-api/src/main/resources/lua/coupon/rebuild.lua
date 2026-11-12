-- Redis 데이터 유실 뒤 자동 복구 (D-4-7): outbox에서 읽은 당첨자로 Set을 다시 채우고 상태 키를 쓴다. 한 스크립트라
-- "Set은 반만 찼는데 상태 키는 써진" 순간이 없다. 캠페인 시작도 같은 경로다(당첨자 0명).
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set   ARGV = 당첨자 userId들
redis.call('DEL', KEYS[2])
local chunk = 1000
for i = 1, #ARGV, chunk do
  local last = math.min(i + chunk - 1, #ARGV)
  redis.call('SADD', KEYS[2], unpack(ARGV, i, last))
end
redis.call('SET', KEYS[1], 'OPEN')
return #ARGV
