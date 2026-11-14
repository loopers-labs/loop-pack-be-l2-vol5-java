-- 대기열 접수: 같은 사용자와 캠페인의 기존 작업 확인, 미완료 한도 확인, 새 작업 저장과 대기 List 추가를 한 번에 처리한다.
-- KEYS[1] 중복 접수 Hash, KEYS[2] 작업 정보 Hash, KEYS[3] 대기 List, KEYS[4] 처리 중 List
-- ARGV[1] 사용자와 캠페인 field, ARGV[2] 새 작업 ID, ARGV[3] 새 작업 JSON, ARGV[4] 미완료 한도
local capacity = tonumber(ARGV[4])
if not ARGV[1] or ARGV[1] == '' or not ARGV[2] or ARGV[2] == '' or not ARGV[3] or ARGV[3] == ''
    or not capacity or capacity < 1 or capacity % 1 ~= 0 then
  return redis.error_reply('coupon queue: invalid submit input')
end

local existingId = redis.call('HGET', KEYS[1], ARGV[1])
if existingId then
  local existingJob = redis.call('HGET', KEYS[2], existingId)
  if not existingJob then
    return redis.error_reply('coupon queue: request index without job')
  end
  return {'EXISTING', existingJob}
end

local unfinished = redis.call('LLEN', KEYS[3]) + redis.call('LLEN', KEYS[4])
if unfinished >= capacity then
  return {'FULL'}
end

if redis.call('HEXISTS', KEYS[2], ARGV[2]) == 1 then
  return redis.error_reply('coupon queue: request id already exists')
end
redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('RPUSH', KEYS[3], ARGV[2])
return {'CREATED', ARGV[3]}
