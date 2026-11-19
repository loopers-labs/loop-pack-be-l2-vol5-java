-- 당첨 판정. KEYS = [meta, winners, running], ARGV = [userId, token, capacity]
local capacity = tonumber(ARGV[3])
if not capacity or capacity < 1 or capacity % 1 ~= 0
    or ARGV[1] == '' or ARGV[2] == '' then
  return redis.error_reply('invalid admission input')
end
local meta = redis.call('HMGET', KEYS[1], 'open', 'active', 'quota')
-- 닫혔거나 meta 를 잃었으면 winners 를 믿을 수 없다
if meta[1] ~= '1' then return 'CLOSED' end
local current = redis.call('HGET', KEYS[2], ARGV[1])
if current == 'PENDING' or current == 'TICKETED' then return current end
-- 종료된 캠페인이어도 이미 받은 사람에게는 같은 결과를 준다
if current then return 'ISSUED:' .. current end
if meta[2] ~= '1' then return 'INACTIVE' end
local quota = tonumber(meta[3])
if not quota then return redis.error_reply('invalid quota') end
if redis.call('HLEN', KEYS[2]) >= quota then return 'SOLD_OUT' end
-- 자리 확인보다 먼저 기록해, 자리가 없어도 순번은 확정한다
redis.call('HSET', KEYS[2], ARGV[1], 'PENDING')
if redis.call('SCARD', KEYS[3]) >= capacity then return 'WON_WAITING' end
redis.call('SADD', KEYS[3], ARGV[2])
return 'WON_RUNNING'
