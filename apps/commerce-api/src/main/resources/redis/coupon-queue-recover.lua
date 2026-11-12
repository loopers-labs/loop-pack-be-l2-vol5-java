-- 수동 복구: 운영자가 이전 worker와 DB 작업의 종료, 실제 발급 결과를 확인한 뒤 redis-cli --eval로 실행한다.
-- 이 스크립트를 실행했다는 사실이 이전 DB 작업 종료의 증거는 아니다.
-- 조회 때 확인한 상태와 실행 token이 그대로일 때만 바꾸고, permit은 그 실행의 token 하나만 반환한다.
-- Lua는 rollback이 없으므로 쓰기 전에 입력, 작업 상태, List 위치를 모두 확인한다.
-- KEYS[1] 작업 정보 Hash, KEYS[2] 대기 List, KEYS[3] 처리 중 List, KEYS[4] permit Set
-- ARGV[1] 작업 ID, ARGV[2] 조회 때 상태, ARGV[3] 조회 때 실행 token
-- ARGV[4] SUCCEED, REQUEUE, FAIL, RELEASE 중 하나, ARGV[5] couponId(SUCCEED), ARGV[6] reasonCode(FAIL)
local requestId, expectedStatus, expectedToken = ARGV[1], ARGV[2], ARGV[3]
local action, couponId, reason = ARGV[4], ARGV[5], ARGV[6]

local function blank(value)
  return value == nil or value == '' or value == cjson.null
end

-- 처리 중이거나 결과 불명인 작업만 상태를 바꾼다. 완료된 작업은 잔여 permit만 정리하고, 대기 작업은 다루지 않는다.
local recoverable = expectedStatus == 'PROCESSING' or expectedStatus == 'UNKNOWN'
local completed = expectedStatus == 'SUCCEEDED' or expectedStatus == 'FAILED'
local validInput = not blank(requestId) and not blank(expectedToken) and (
  (action == 'SUCCEED' and recoverable and not blank(couponId) and blank(reason))
  or (action == 'REQUEUE' and recoverable and blank(couponId) and blank(reason))
  or (action == 'FAIL' and recoverable and blank(couponId) and not blank(reason) and reason ~= 'DB_RESULT_UNKNOWN')
  or (action == 'RELEASE' and completed and blank(couponId) and blank(reason)))
if not validInput then
  return redis.error_reply('coupon queue: invalid recover input')
end

local json = redis.call('HGET', KEYS[1], requestId)
if not json then
  return redis.error_reply('coupon queue: recover target without job')
end
local decoded, job = pcall(cjson.decode, json)
if not decoded or type(job) ~= 'table' then
  return redis.error_reply('coupon queue: stored job is not valid')
end

if job['status'] ~= expectedStatus or job['executionToken'] ~= expectedToken then
  return {'MISMATCH', json}
end

local inWaiting = redis.call('LPOS', KEYS[2], requestId)
local inProcessing = redis.call('LPOS', KEYS[3], requestId)
if (recoverable and (not inProcessing or inWaiting)) or (completed and (inProcessing or inWaiting)) then
  return redis.error_reply('coupon queue: recover target list position is inconsistent')
end

local released = redis.call('SREM', KEYS[4], expectedToken)
if action == 'RELEASE' then
  return {'APPLIED', json, tostring(released)}
end

if action == 'SUCCEED' then
  job['status'] = 'SUCCEEDED'
  job['couponId'] = couponId
  job['reasonCode'] = cjson.null
elseif action == 'FAIL' then
  job['status'] = 'FAILED'
  job['couponId'] = cjson.null
  job['reasonCode'] = reason
else
  job['status'] = 'WAITING'
  job['couponId'] = cjson.null
  job['reasonCode'] = cjson.null
  job['executionToken'] = cjson.null
end
local updated = cjson.encode(job)
redis.call('HSET', KEYS[1], requestId, updated)
redis.call('LREM', KEYS[3], 1, requestId)
if action == 'REQUEUE' then
  redis.call('RPUSH', KEYS[2], requestId)
end
return {'APPLIED', updated, tostring(released)}
