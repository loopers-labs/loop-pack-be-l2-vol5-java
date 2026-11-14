-- 시스템 실패 재접수: 작업이 여전히 기대한 사유의 실패일 때만 같은 작업 ID로 다시 대기시키거나,
-- 재접수 중 확인한 쿠폰으로 성공 처리한다. 작업 ID, 접수 시각, 중복 접수 정보는 그대로 둔다.
-- Lua는 rollback이 없으므로 쓰기 전에 입력, 작업 상태, List 위치, 한도를 모두 확인한다.
-- KEYS[1] 작업 정보 Hash, KEYS[2] 대기 List, KEYS[3] 처리 중 List
-- ARGV[1] 작업 ID, ARGV[2] 기대 사유, ARGV[3] REQUEUE 또는 SUCCEED, ARGV[4] 미완료 한도, ARGV[5] couponId
local requestId, expectedReason, mode, couponId = ARGV[1], ARGV[2], ARGV[3], ARGV[5]
local capacity = tonumber(ARGV[4])

local validInput = requestId and requestId ~= ''
  and (expectedReason == 'DB_ERROR' or expectedReason == 'INTERNAL_ERROR')
  and ((mode == 'REQUEUE' and capacity and capacity >= 1 and capacity % 1 == 0)
    or (mode == 'SUCCEED' and couponId and couponId ~= ''))
if not validInput then
  return redis.error_reply('coupon queue: invalid resubmit input')
end

local json = redis.call('HGET', KEYS[1], requestId)
if not json then
  return redis.error_reply('coupon queue: resubmit target without job')
end
local decoded, job = pcall(cjson.decode, json)
if not decoded or type(job) ~= 'table' then
  return redis.error_reply('coupon queue: stored job is not valid')
end

if job['status'] ~= 'FAILED' or job['reasonCode'] ~= expectedReason then
  return {'CHANGED', json}
end
if redis.call('LPOS', KEYS[2], requestId) or redis.call('LPOS', KEYS[3], requestId) then
  return redis.error_reply('coupon queue: failed job still in queue list')
end

if mode == 'SUCCEED' then
  job['status'] = 'SUCCEEDED'
  job['couponId'] = couponId
  job['reasonCode'] = cjson.null
  local succeeded = cjson.encode(job)
  redis.call('HSET', KEYS[1], requestId, succeeded)
  return {'SUCCEEDED', succeeded}
end

if redis.call('LLEN', KEYS[2]) + redis.call('LLEN', KEYS[3]) >= capacity then
  return {'FULL', json}
end
job['status'] = 'WAITING'
job['couponId'] = cjson.null
job['reasonCode'] = cjson.null
job['executionToken'] = cjson.null
local requeued = cjson.encode(job)
redis.call('HSET', KEYS[1], requestId, requeued)
redis.call('RPUSH', KEYS[2], requestId)
return {'REQUEUED', requeued}
