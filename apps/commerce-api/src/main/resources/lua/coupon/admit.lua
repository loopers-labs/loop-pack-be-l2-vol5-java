-- 입장 (D-4-3): 모든 서버가 구간마다 부르지만, 구간 카운터 하나가 "이번 구간에 몇 명 들였나"를 세서 서버 수와 무관하게 구간당 N명만 들인다.
-- 남은 쿠폰 수만큼만 들인다 (학습자 11-12): 남은 자리 = 당첨자 수 − 당첨자 − 입장했지만 아직 발급 안 한 사람.
--   그래서 줄 순서 = 당첨 순서다. 입장하고 떠난 사람은 입장 키가 만료되면(입장 기록의 점수 = 만료 시각) 자리가 풀려 다음 사람이 들어온다.
-- 꺼내기(ZPOPMIN)와 입장 키 쓰기를 한 스크립트로 한다 — 따로 하면 꺼낸 직후 서버가 죽을 때 그 사람이 줄에서도 빠지고 키도 없이 사라진다.
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set  KEYS[3] 줄  KEYS[4] 입장 기록(ZSET, score = 입장 키 만료 ms)
-- ARGV[1] 당첨자 수  ARGV[2] 구간당 입장 수  ARGV[3] 구간 길이(ms)  ARGV[4] 입장 키 TTL(초)
-- ARGV[5] 구간 카운터 키 앞부분  ARGV[6] 입장 키 앞부분 (뒤에 userId)
-- 반환: {상태, 들인 수}  상태 = UNKNOWN | SOLD_OUT | OK
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'UNKNOWN', 0}
end
local winners = redis.call('SCARD', KEYS[2])
if winners >= tonumber(ARGV[1]) then
  redis.call('UNLINK', KEYS[3])   -- 품절: 줄을 지운다. 기다리던 사람은 다음 조회에서 SOLD_OUT (D-4-6)
  return {'SOLD_OUT', 0}
end
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', nowMs)  -- 입장 키가 만료된 사람(떠난 사람)의 자리를 푼다
local remaining = tonumber(ARGV[1]) - winners - redis.call('ZCARD', KEYS[4])
local tick = math.floor(nowMs / tonumber(ARGV[3]))
local usedKey = ARGV[5] .. tick
local used = tonumber(redis.call('GET', usedKey) or '0')
local room = math.min(tonumber(ARGV[2]) - used, remaining)
if room <= 0 then
  return {'OK', 0}
end
local popped = redis.call('ZPOPMIN', KEYS[3], room)
local admitted = #popped / 2
if admitted == 0 then
  return {'OK', 0}
end
local expiresAt = nowMs + tonumber(ARGV[4]) * 1000
for i = 1, #popped, 2 do
  redis.call('SET', ARGV[6] .. popped[i], '1', 'EX', tonumber(ARGV[4]))
  redis.call('ZADD', KEYS[4], expiresAt, popped[i])
end
redis.call('INCRBY', usedKey, admitted)
redis.call('PEXPIRE', usedKey, tonumber(ARGV[3]) * 2)  -- 구간 길이의 2배: 구간이 끝나기 전에 카운터가 사라지지 않게
return {'OK', admitted}
