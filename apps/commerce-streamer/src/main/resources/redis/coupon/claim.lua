-- 오래 처리 중인(죽은 worker 의) 항목을 이 consumer 로 가져오고 전달 횟수를 함께 돌려준다 (8주차 D-119, D-131, D-173)
-- KEYS: 1 stream
-- ARGV: 1 group, 2 consumer, 3 minIdleMillis, 4 count
-- 돌려주는 값: { {id, fields, deliveries}, ... }. Stream 에서 지워진 항목은 건너뛴다
local claimed = redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], '0-0', 'COUNT', tonumber(ARGV[4]))
local result = {}
for _, entry in ipairs(claimed[2]) do
  if entry and entry[2] then
    local pending = redis.call('XPENDING', KEYS[1], ARGV[1], entry[1], entry[1], 1)
    local deliveries = pending[1] and pending[1][4] or 1
    result[#result + 1] = {entry[1], entry[2], deliveries}
  end
end
return result
