-- 접수 Lua 1: 표식 확인 · 중복 확인 · 순번 · 구간 판정 (8주차 D-115, D-117, D-121, D-133, D-139)
-- KEYS: 1 epoch, 2 sequence, 3 users, 4 pending, 5 sold-out, 6 promoted
-- ARGV: 1 userId, 2 reserveLimit(한도 + 예비), 3 nowMillis
-- 돌려주는 값: {'CLOSED'} | {'EXISTING', 저장값, 품절(0/1), 승격 인원} | {'SOLD_OUT'} | {'PENDING', 순번}
-- 원자적이지만 rollback 은 없으므로 입력 검증을 먼저 하고, 쓰기는 INCR → HSET → ZADD 순서로 한다
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'CLOSED'}
end
local userId = ARGV[1]
local reserveLimit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
if userId == nil or userId == '' or not reserveLimit or reserveLimit < 1 or reserveLimit % 1 ~= 0 or not now then
  return redis.error_reply('invalid coupon accept input')
end
local existing = redis.call('HGET', KEYS[3], userId)
if existing then
  return {'EXISTING', existing, redis.call('EXISTS', KEYS[5]), redis.call('GET', KEYS[6]) or '0'}
end
if redis.call('EXISTS', KEYS[5]) == 1 then
  return {'SOLD_OUT'}
end
-- 예비 범위가 다 찼으면 순번을 더 쓰지 않는다
if tonumber(redis.call('GET', KEYS[2]) or '0') >= reserveLimit then
  return {'SOLD_OUT'}
end
local sequence = redis.call('INCR', KEYS[2])
redis.call('HSET', KEYS[3], userId, 'PENDING_CONFIRM:' .. sequence)
redis.call('ZADD', KEYS[4], now, userId)
return {'PENDING', tostring(sequence)}
