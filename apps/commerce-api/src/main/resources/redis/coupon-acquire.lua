-- 당첨자의 자리 재요청. KEYS = [meta, winners, running], ARGV = [userId, token, capacity]
local capacity = tonumber(ARGV[3])
if not capacity or capacity < 1 or capacity % 1 ~= 0
    or ARGV[1] == '' or ARGV[2] == '' then
  return redis.error_reply('invalid admission input')
end
if redis.call('HGET', KEYS[1], 'open') ~= '1' then return 'CLOSED' end
local current = redis.call('HGET', KEYS[2], ARGV[1])
if current ~= 'PENDING' and current ~= 'TICKETED' then return 'NOT_PENDING' end
-- 앞선 응답이 불확실했던 같은 실행만 자기 자리를 다시 확인한다
if redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 1 then return 'ACQUIRED' end
if redis.call('SCARD', KEYS[3]) >= capacity then return 'FULL' end
redis.call('SADD', KEYS[3], ARGV[2])
return 'ACQUIRED'
