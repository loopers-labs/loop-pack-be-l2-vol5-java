-- 입장 (D-4-3): 모든 서버가 구간마다 부르지만, 구간 카운터 하나가 "이번 구간에 몇 명 들였나"를 세서 서버 수와 무관하게 구간당 N명만 들인다.
-- 꺼내기(ZPOPMIN)와 입장 키 쓰기를 한 스크립트로 한다 — 따로 하면 꺼낸 직후 서버가 죽을 때 그 사람이 줄에서도 빠지고 키도 없이 사라진다.
-- KEYS[1] 상태 키  KEYS[2] 당첨자 Set  KEYS[3] 줄
-- ARGV[1] 당첨자 수  ARGV[2] 구간당 입장 수  ARGV[3] 구간 길이(ms)  ARGV[4] 입장 키 TTL(초)
-- ARGV[5] 구간 카운터 키 앞부분  ARGV[6] 입장 키 앞부분 (뒤에 userId)
-- 반환: {상태, 들인 수}  상태 = UNKNOWN | SOLD_OUT | OK
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'UNKNOWN', 0}
end
if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[1]) then
  redis.call('UNLINK', KEYS[3])   -- 품절: 줄을 지운다. 기다리던 사람은 다음 조회에서 SOLD_OUT (D-4-6)
  return {'SOLD_OUT', 0}
end
local t = redis.call('TIME')
local tick = math.floor((tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)) / tonumber(ARGV[3]))
local usedKey = ARGV[5] .. tick
local used = tonumber(redis.call('GET', usedKey) or '0')
local room = tonumber(ARGV[2]) - used
if room <= 0 then
  return {'OK', 0}
end
local popped = redis.call('ZPOPMIN', KEYS[3], room)
local admitted = #popped / 2
if admitted == 0 then
  return {'OK', 0}
end
for i = 1, #popped, 2 do
  redis.call('SET', ARGV[6] .. popped[i], '1', 'EX', tonumber(ARGV[4]))
end
redis.call('INCRBY', usedKey, admitted)
redis.call('PEXPIRE', usedKey, 2000)
return {'OK', admitted}
