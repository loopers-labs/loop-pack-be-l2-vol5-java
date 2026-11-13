-- 접수 Lua 2: Kafka 확인 뒤 PENDING_CONFIRM 을 WAITING(대기열) · RESERVE(예비 목록)로 확정 (8주차 D-121, D-133)
-- KEYS: 1 epoch, 2 users, 3 pending, 4 stream, 5 reserve, 6 sold-out, 7 promoted
-- ARGV: 1 userId, 2 totalQuota
-- 돌려주는 값: {'CLOSED'} | {'NONE'} | {'STATE', 저장값, 품절(0/1), 승격 인원}
-- 이미 확정됐으면 바꾸지 않고 돌려준다. 재전송 · 대사가 같은 사람을 다시 확정해도 한 번만 반영된다
local epoch = redis.call('GET', KEYS[1])
if not epoch then
  return {'CLOSED'}
end
local userId = ARGV[1]
local totalQuota = tonumber(ARGV[2])
if userId == nil or userId == '' or not totalQuota or totalQuota < 1 then
  return redis.error_reply('invalid coupon confirm input')
end
local stored = redis.call('HGET', KEYS[2], userId)
if not stored then
  return {'NONE'}
end
local state, sequence = string.match(stored, '^([A-Z_]+):(%d+)')
if state == 'PENDING_CONFIRM' then
  if tonumber(sequence) <= totalQuota then
    redis.call('XADD', KEYS[4], '*', 'userId', userId, 'sequence', sequence, 'slotNo', sequence, 'epoch', epoch)
    stored = 'WAITING:' .. sequence
  else
    redis.call('ZADD', KEYS[5], tonumber(sequence), userId)
    stored = 'RESERVE:' .. sequence
  end
  redis.call('HSET', KEYS[2], userId, stored)
  redis.call('ZREM', KEYS[3], userId)
end
return {'STATE', stored, redis.call('EXISTS', KEYS[6]), redis.call('GET', KEYS[7]) or '0'}
