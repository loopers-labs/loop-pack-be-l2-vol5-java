-- 작업 가져오기: 대기 List 맨 앞의 작업을 처리 중 List로 옮기고, 작업을 처리 중으로 바꾸며 실행 token을 기록한다.
-- permit을 이미 얻은 실행만 호출한다. Lua는 rollback이 없으므로 쓰기 전에 대상 작업을 먼저 확인한다.
-- KEYS[1] 대기 List, KEYS[2] 처리 중 List, KEYS[3] 작업 정보 Hash
-- ARGV[1] 실행 token
local token = ARGV[1]
if not token or token == '' then
  return redis.error_reply('coupon queue: invalid claim input')
end

local requestId = redis.call('LINDEX', KEYS[1], 0)
if not requestId then
  return {'EMPTY'}
end

local json = redis.call('HGET', KEYS[3], requestId)
if not json then
  return redis.error_reply('coupon queue: waiting entry without job')
end
local decoded, job = pcall(cjson.decode, json)
if not decoded or type(job) ~= 'table' or job['status'] ~= 'WAITING' then
  return redis.error_reply('coupon queue: waiting entry is not a waiting job')
end

redis.call('LMOVE', KEYS[1], KEYS[2], 'LEFT', 'RIGHT')
job['status'] = 'PROCESSING'
job['executionToken'] = token
local updated = cjson.encode(job)
redis.call('HSET', KEYS[3], requestId, updated)
return {'CLAIMED', updated}
