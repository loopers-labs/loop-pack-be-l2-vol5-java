-- 캠페인 열기의 Redis 쪽 (8주차 D-166). DB 가 OPEN 이 된 뒤 부른다.
-- 메타를 먼저 쓰고 표식을 마지막에 써서, 중간에 멈추면 표식이 없어 접수는 닫힘으로 보인다 (D-139)
-- KEYS: 1 epoch, 2 meta
-- ARGV: 1 totalQuota, 2 reserveLimit, 3 epoch
-- 돌려주는 값: 현재 epoch. 이미 열려 있으면 바꾸지 않는다
local totalQuota = tonumber(ARGV[1])
local reserveLimit = tonumber(ARGV[2])
if not totalQuota or totalQuota < 1 or not reserveLimit or reserveLimit < totalQuota or ARGV[3] == '' then
  return redis.error_reply('invalid coupon open input')
end
redis.call('HSET', KEYS[2], 'total-quota', ARGV[1], 'reserve-limit', ARGV[2], 'status', 'OPEN')
redis.call('SET', KEYS[1], ARGV[3], 'NX')
return redis.call('GET', KEYS[1])
