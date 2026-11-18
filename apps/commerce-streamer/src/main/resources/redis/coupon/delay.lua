-- 안쪽 재시도로 끝내지 못한 항목을 놓는다: XACK + 지연 ZSET(점수 = 다음 시도 시각) (8주차 D-128, D-129)
-- 항목 내용(시도 정보 포함)은 member 로 들고 다닌다. Stream 으로 돌아오면 새 ID 를 받기 때문이다
-- KEYS: 1 stream, 2 delayed
-- ARGV: 1 group, 2 entryId, 3 nextAtMillis, 4 member
redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), ARGV[4])
return redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
