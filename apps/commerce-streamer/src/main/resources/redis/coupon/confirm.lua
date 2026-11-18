-- 접수 Lua 2 와 같은 확정 (commerce-api 와 같은 내용). worker 의 확인 대기 정리가 재전송 뒤 부른다 (8주차 D-121, D-133, D-134)
-- KEYS: 1 epoch, 2 meta, 3 users, 4 pending, 5 stream, 6 reserve, 7 sold-out, 8 promoted
-- ARGV: 1 userId
-- 돌려주는 값: {'CLOSED'} | {'NONE'} | {'STATE', 저장값, 품절(0/1), 승격 인원, 한도}
-- 이미 확정됐으면 바꾸지 않고 돌려준다. 재전송 · 대사가 같은 사람을 다시 확정해도 한 번만 반영된다
local epoch = redis.call('GET', KEYS[1])
if not epoch then
  return {'CLOSED'}
end
local userId = ARGV[1]
local totalQuota = tonumber(redis.call('HGET', KEYS[2], 'total-quota'))
if userId == nil or userId == '' or not totalQuota then
  return redis.error_reply('invalid coupon confirm input or meta')
end
local stored = redis.call('HGET', KEYS[3], userId)
if not stored then
  return {'NONE'}
end
local state, sequence = string.match(stored, '^([A-Z_]+):(%d+)')
if state == 'PENDING_CONFIRM' then
  if tonumber(sequence) <= totalQuota then
    redis.call('XADD', KEYS[5], '*', 'userId', userId, 'sequence', sequence, 'slotNo', sequence, 'epoch', epoch)
    stored = 'WAITING:' .. sequence
  else
    redis.call('ZADD', KEYS[6], tonumber(sequence), userId)
    stored = 'RESERVE:' .. sequence
  end
  redis.call('HSET', KEYS[3], userId, stored)
  redis.call('ZREM', KEYS[4], userId)
end
return {'STATE', stored, redis.call('EXISTS', KEYS[7]), redis.call('GET', KEYS[8]) or '0', tostring(totalQuota)}
