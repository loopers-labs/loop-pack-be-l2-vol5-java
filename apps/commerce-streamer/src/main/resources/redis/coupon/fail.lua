-- 최종 실패: 사용자 기록 FAILED, 필요하면 가장 앞의 예비를 승격하고 XACK (8주차 D-120, D-123, D-124)
-- 이미 FAILED · ISSUED 면 승격하지 않는다. 같은 실패를 두 번 처리해도 한 명만 승격된다
-- 승격은 슬롯 이전이다: 예비는 실패한 사람의 슬롯을 물려받는다 (D-141)
-- KEYS: 1 users, 2 stream, 3 reserve, 4 promoted, 5 sold-out, 6 meta
-- ARGV: 1 userId, 2 group, 3 entryId, 4 slotNo, 5 promote(1/0), 6 epoch
-- 돌려주는 값: 승격한 사용자 id, 승격하지 않았으면 ''
local stored = redis.call('HGET', KEYS[1], ARGV[1])
local state, sequence = nil, nil
if stored then
  state, sequence = string.match(stored, '^([A-Z_]+):(%d+)')
end
local promotedUser = ''
if state ~= 'FAILED' and state ~= 'ISSUED' then
  redis.call('HSET', KEYS[1], ARGV[1], 'FAILED:' .. (sequence or '0'))
  local open = redis.call('HGET', KEYS[6], 'status') == 'OPEN'
  if ARGV[5] == '1' and open and redis.call('EXISTS', KEYS[5]) == 0 then
    local popped = redis.call('ZPOPMIN', KEYS[3], 1)
    if popped[1] then
      promotedUser = popped[1]
      local promotedSequence = popped[2]
      redis.call('HSET', KEYS[1], promotedUser, 'WAITING:' .. promotedSequence)
      redis.call('INCR', KEYS[4])
      redis.call('XADD', KEYS[2], '*', 'userId', promotedUser, 'sequence', promotedSequence, 'slotNo', ARGV[4],
        'epoch', ARGV[6], 'unclassified', '0', 'transient', '0')
    end
  end
end
redis.call('XACK', KEYS[2], ARGV[2], ARGV[3])
return promotedUser
