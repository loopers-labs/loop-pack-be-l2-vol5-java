-- 접수 Lua 1: 표식 확인 · 중복 확인 · 순번 · 구간 판정 (8주차 D-115, D-117, D-121, D-133, D-139)
-- 한도 · 예비 범위 · 상태는 열 때 쓴 메타에서 읽어, 판정 전에 DB 를 거치지 않는다
-- KEYS: 1 epoch, 2 meta, 3 sequence, 4 users, 5 pending, 6 sold-out, 7 promoted
-- ARGV: 1 userId, 2 nowMillis
-- 돌려주는 값: {'CLOSED'} | {'EXISTING', 저장값, 품절(0/1), 승격 인원, 한도} | {'NOT_ACTIVE'} | {'SOLD_OUT'} | {'PENDING', 순번}
-- 원자적이지만 rollback 은 없으므로 입력 검증을 먼저 하고, 쓰기는 INCR → HSET → ZADD 순서로 한다
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'CLOSED'}
end
local userId = ARGV[1]
local now = tonumber(ARGV[2])
local meta = redis.call('HMGET', KEYS[2], 'total-quota', 'reserve-limit', 'status')
local totalQuota = tonumber(meta[1])
local reserveLimit = tonumber(meta[2])
if userId == nil or userId == '' or not now or not totalQuota or not reserveLimit then
  return redis.error_reply('invalid coupon accept input or meta')
end
local existing = redis.call('HGET', KEYS[4], userId)
if existing then
  return {'EXISTING', existing, redis.call('EXISTS', KEYS[6]), redis.call('GET', KEYS[7]) or '0', tostring(totalQuota)}
end
if meta[3] ~= 'OPEN' then
  return {'NOT_ACTIVE'}
end
if redis.call('EXISTS', KEYS[6]) == 1 then
  return {'SOLD_OUT'}
end
-- 예비 범위가 다 찼으면 순번을 더 쓰지 않는다
if tonumber(redis.call('GET', KEYS[3]) or '0') >= reserveLimit then
  return {'SOLD_OUT'}
end
local sequence = redis.call('INCR', KEYS[3])
redis.call('HSET', KEYS[4], userId, 'PENDING_CONFIRM:' .. sequence)
redis.call('ZADD', KEYS[5], now, userId)
return {'PENDING', tostring(sequence)}
