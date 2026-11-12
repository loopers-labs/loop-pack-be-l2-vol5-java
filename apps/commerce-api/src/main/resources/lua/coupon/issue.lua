-- 발급 판정 (docs/week8/plan.md D-2 판정 순서, D-4-4 입장 키, D-4-6 품절)
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set  KEYS[3] 이 사용자의 입장 키
-- ARGV[1] userId  ARGV[2] 당첨자 수(total)
-- 확인과 추가를 한 스크립트로 한다 — 실행 중 다른 명령이 끼어들지 않아 "확인 후 추가" 사이에 다른 사람이 들어올 수 없다.
if redis.call('EXISTS', KEYS[1]) == 0 then
  return 'UNKNOWN'          -- 상태 키 없음: Redis 데이터를 믿을 수 없다 (D-4-7)
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return 'ALREADY'          -- 이미 당첨 — 입장 키보다 먼저 본다 (다시 요청하는 당첨자는 입장 키가 만료됐을 수 있다)
end
if redis.call('EXISTS', KEYS[3]) == 0 then
  return 'NOT_ADMITTED'
end
if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[2]) then
  return 'SOLD_OUT'
end
redis.call('SADD', KEYS[2], ARGV[1])
return 'ACCEPTED'
