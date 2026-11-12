-- 대기열 등록 (D-4-2 ZSET·순번, D-4-1a 항상 줄을 거침, D-4-3 바로 입장, D-4-5 줄 길이 제한, D-4-6 품절)
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set  KEYS[3] 줄(ZSET)  KEYS[4] 순번 카운터  KEYS[5] 이 사용자의 입장 키
-- ARGV[1] userId  ARGV[2] 당첨자 수  ARGV[3] 줄 길이 제한  ARGV[4] 구간당 입장 수  ARGV[5] 구간 길이(ms)
-- ARGV[6] 입장 키 TTL(초)  ARGV[7] 구간 카운터 키 앞부분 (뒤에 구간 번호를 붙인다)
-- 반환: {상태, 앞 인원}  상태 = UNKNOWN | ISSUED | SOLD_OUT | ADMITTED | WAITING | QUEUE_FULL
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'UNKNOWN', -1}
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return {'ISSUED', -1}
end
if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[2]) then
  return {'SOLD_OUT', -1}
end
if redis.call('EXISTS', KEYS[5]) == 1 then
  return {'ADMITTED', -1}
end
local rank = redis.call('ZRANK', KEYS[3], ARGV[1])
if rank then
  return {'WAITING', rank}  -- 이미 줄에 있으면 원래 자리 그대로
end
local waiting = redis.call('ZCARD', KEYS[3])
if waiting == 0 then
  -- 줄이 비어 있고 이번 구간 한도가 남았으면 바로 입장 (기다리는 사람이 있으면 새치기가 되므로 하지 않는다)
  local t = redis.call('TIME')
  local tick = math.floor((tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)) / tonumber(ARGV[5]))
  local usedKey = ARGV[7] .. tick
  local used = tonumber(redis.call('GET', usedKey) or '0')
  if used < tonumber(ARGV[4]) then
    redis.call('INCR', usedKey)
    redis.call('PEXPIRE', usedKey, 2000)
    redis.call('SET', KEYS[5], '1', 'EX', tonumber(ARGV[6]))
    return {'ADMITTED', -1}
  end
end
if waiting >= tonumber(ARGV[3]) then
  return {'QUEUE_FULL', -1}
end
local seq = redis.call('INCR', KEYS[4])
redis.call('ZADD', KEYS[3], seq, ARGV[1])
return {'WAITING', redis.call('ZRANK', KEYS[3], ARGV[1])}
