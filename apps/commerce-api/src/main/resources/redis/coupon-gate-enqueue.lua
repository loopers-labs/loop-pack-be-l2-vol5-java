-- 작업 큐 모드의 접수. 판단(enter와 같은 규칙)과 작업 등록을 한 번에 한다. 차감은 됐는데 작업이 없는 상태가 생기지 않는다.
-- Lua는 중간 오류를 rollback하지 않으므로, 모든 키의 상태를 쓰기 전에 확인한다. 큐가 없으면(준비 중) 쓰지 않는다.
-- KEYS: stock(hash), users(hash), jobs(stream). ARGV: userId, now(epoch ms), reservation(R:UUID)
local stockType = redis.call('TYPE', KEYS[1]).ok
local userType = redis.call('TYPE', KEYS[2]).ok
local jobsType = redis.call('TYPE', KEYS[3]).ok
if stockType == 'none' or jobsType == 'none' then return {'NOT_PREPARED', ''} end
if stockType ~= 'hash' or (userType ~= 'none' and userType ~= 'hash') or jobsType ~= 'stream' then
  return redis.error_reply('invalid coupon gate key type')
end
local state = redis.call('HGET', KEYS[2], ARGV[1])
if state == 'A' then return {'ALREADY_ACCEPTED', ''} end
if state == 'C' then return {'CANCELLED', ''} end
if state then return {'ALREADY_RESERVED', ''} end
local remaining = tonumber(redis.call('HGET', KEYS[1], 'remaining'))
local endAt = tonumber(redis.call('HGET', KEYS[1], 'endAt'))
local expireAt = tonumber(redis.call('HGET', KEYS[1], 'expireAt'))
local now = tonumber(ARGV[2])
if not remaining or not endAt or not expireAt or not now or not ARGV[3] or ARGV[3] == '' then
  return redis.error_reply('invalid coupon gate data')
end
if now >= endAt then return {'ENDED', ''} end
if remaining <= 0 then
  -- 저장 전 예약(pending)이 남아 있으면 취소로 자리가 돌아올 수 있다. 0일 때만 다시 풀리지 않는 매진이다.
  if tonumber(redis.call('HGET', KEYS[1], 'pending')) == 0 then return {'SOLD_OUT_FINAL', ''} end
  return {'SOLD_OUT', ''}
end
redis.call('HINCRBY', KEYS[1], 'remaining', -1)
redis.call('HINCRBY', KEYS[1], 'pending', 1)
redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
redis.call('PEXPIREAT', KEYS[2], expireAt)
redis.call('XADD', KEYS[3], '*', 'userId', ARGV[1], 'reservation', ARGV[3], 'acceptedAt', ARGV[2])
return {'PASSED', ARGV[3]}
