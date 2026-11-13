-- 접수 뒤 T 가 지나도 확정되지 않은 확인 대기를 사용자 기록의 순번과 함께 돌려준다 (8주차 D-134)
-- 이미 PENDING_CONFIRM 이 아닌 사람(확정됐는데 목록에 남은 경우)은 목록에서 지운다
-- KEYS: 1 pending-confirm, 2 users
-- ARGV: 1 acceptedBeforeMillis, 2 limit
-- 돌려주는 값: { {userId, sequence, acceptedAtMillis}, ... }
local stale = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[2]))
local result = {}
for i = 1, #stale, 2 do
  local userId = stale[i]
  local stored = redis.call('HGET', KEYS[2], userId)
  local state, sequence = nil, nil
  if stored then
    state, sequence = string.match(stored, '^([A-Z_]+):(%d+)')
  end
  if state == 'PENDING_CONFIRM' then
    result[#result + 1] = {userId, sequence, stale[i + 1]}
  else
    redis.call('ZREM', KEYS[1], userId)
  end
end
return result
