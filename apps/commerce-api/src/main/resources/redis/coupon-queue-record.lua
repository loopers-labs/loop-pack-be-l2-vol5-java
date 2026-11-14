-- 처리 결과 기록: 실행 token이 같고 처리 중인 작업만 성공, 실패, 결과 불명으로 기록한다.
-- 성공과 실패는 처리 중 List에서 그 작업만 지우고, 결과 불명은 처리 중 List에 남긴다. 실행 token은 남긴다.
-- Lua는 rollback이 없으므로 쓰기 전에 입력, 작업 정보, 처리 중 List 위치를 모두 확인한다.
-- KEYS[1] 작업 정보 Hash, KEYS[2] 처리 중 List
-- ARGV[1] 작업 ID, ARGV[2] 실행 token, ARGV[3] 기록할 상태, ARGV[4] couponId, ARGV[5] reasonCode
local requestId, token, target, couponId, reason = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5]

local function blank(value)
  return value == nil or value == '' or value == cjson.null
end

local function text(value)
  if blank(value) then
    return ''
  end
  return value
end

local validInput = not blank(requestId) and not blank(token) and (
  (target == 'SUCCEEDED' and not blank(couponId) and blank(reason))
  or (target == 'FAILED' and blank(couponId) and not blank(reason) and reason ~= 'DB_RESULT_UNKNOWN')
  or (target == 'UNKNOWN' and blank(couponId) and reason == 'DB_RESULT_UNKNOWN'))
if not validInput then
  return redis.error_reply('coupon queue: invalid record input')
end

local json = redis.call('HGET', KEYS[1], requestId)
if not json then
  return redis.error_reply('coupon queue: record target without job')
end
local decoded, job = pcall(cjson.decode, json)
if not decoded or type(job) ~= 'table' then
  return redis.error_reply('coupon queue: stored job is not valid')
end

if job['executionToken'] ~= token then
  return {'REJECTED', json}
end

if job['status'] == 'PROCESSING' then
  if not redis.call('LPOS', KEYS[2], requestId) then
    return redis.error_reply('coupon queue: processing job missing from processing list')
  end
  job['status'] = target
  job['couponId'] = blank(couponId) and cjson.null or couponId
  job['reasonCode'] = blank(reason) and cjson.null or reason
  local updated = cjson.encode(job)
  redis.call('HSET', KEYS[1], requestId, updated)
  if target ~= 'UNKNOWN' then
    redis.call('LREM', KEYS[2], 1, requestId)
  end
  return {'APPLIED', updated}
end

if job['status'] == target and text(job['couponId']) == couponId and text(job['reasonCode']) == reason then
  return {'ALREADY_APPLIED', json}
end
return {'REJECTED', json}
