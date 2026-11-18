-- 시각이 된 지연 항목을 Stream 으로 되돌린다(XADD + ZREM). 여러 worker 가 동시에 불러도 한 번만 옮긴다 (8주차 D-129, D-130)
-- member 형식: userId|sequence|slotNo|epoch|unclassified|transient
-- KEYS: 1 delayed, 2 stream
-- ARGV: 1 nowMillis, 2 limit
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
for _, member in ipairs(due) do
  local f = {}
  for part in string.gmatch(member, '([^|]+)') do
    f[#f + 1] = part
  end
  redis.call('XADD', KEYS[2], '*', 'userId', f[1], 'sequence', f[2], 'slotNo', f[3], 'epoch', f[4],
    'unclassified', f[5], 'transient', f[6])
  redis.call('ZREM', KEYS[1], member)
end
return #due
