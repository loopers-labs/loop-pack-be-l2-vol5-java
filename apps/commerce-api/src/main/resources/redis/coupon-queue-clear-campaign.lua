-- seed reset 전 캠페인 대기열 정리: 남은 실행이 없을 때만 해당 캠페인의 작업 정보와 중복 접수 정보를 지운다.
-- permit Set은 캠페인을 구분하지 않으므로 다른 캠페인의 token도 정리를 막는다. 쓰기 전에 모든 조건을 확인한다.
-- KEYS[1] 중복 접수 Hash, KEYS[2] 작업 정보 Hash, KEYS[3] 대기 List, KEYS[4] 처리 중 List, KEYS[5] permit Set
-- ARGV[1] 캠페인 ID
local campaignId = ARGV[1]
if not campaignId or not string.match(campaignId, '^%d+$') then
  return redis.error_reply('coupon queue: invalid clear input')
end

if redis.call('SCARD', KEYS[5]) > 0 then
  return {'BLOCKED', 'permit token remains'}
end

for _, listKey in ipairs({KEYS[3], KEYS[4]}) do
  for _, requestId in ipairs(redis.call('LRANGE', listKey, 0, -1)) do
    local json = redis.call('HGET', KEYS[2], requestId)
    if not json then
      return redis.error_reply('coupon queue: list entry without job')
    end
    local decoded, job = pcall(cjson.decode, json)
    if not decoded or type(job) ~= 'table' then
      return redis.error_reply('coupon queue: stored job is not valid')
    end
    if job['campaignId'] == campaignId then
      return {'BLOCKED', 'unfinished request remains'}
    end
  end
end

local prefix = campaignId .. ':'
local entries = redis.call('HGETALL', KEYS[1])
local removed = 0
for index = 1, #entries, 2 do
  local field, requestId = entries[index], entries[index + 1]
  if string.sub(field, 1, #prefix) == prefix then
    redis.call('HDEL', KEYS[2], requestId)
    redis.call('HDEL', KEYS[1], field)
    removed = removed + 1
  end
end
return {'CLEARED', tostring(removed)}
