-- Redis 판단은 수량·중복을 먼저 거른다. R은 시간만으로 회수하거나 다른 실행에 넘기지 않는다.
-- KEYS: stock(hash), users(hash). ARGV: userId, now(epoch ms), reservation(R:UUID)
local stockType = redis.call('TYPE', KEYS[1]).ok
local userType = redis.call('TYPE', KEYS[2]).ok
if stockType == 'none' then return {'NOT_PREPARED', ''} end
if stockType ~= 'hash' or (userType ~= 'none' and userType ~= 'hash') then return redis.error_reply('invalid coupon gate key type') end
local state = redis.call('HGET', KEYS[2], ARGV[1])
if state == 'A' then return {'ALREADY_ACCEPTED', ''} end
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
return {'PASSED', ARGV[3]}
