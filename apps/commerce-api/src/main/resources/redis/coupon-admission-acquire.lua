-- 쿠폰 발급 동시 처리 자리 잡기(docs/week8/decisions.md D-3, D-4, D-5).
-- KEYS[1] = 자리 Set 키, ARGV[1] = 이번 실행의 token, ARGV[2] = 수용량 C
-- 반환: 1 = 수용(자리 있음), 0 = 거절(자리 없음)
-- 확인과 추가를 한 번에 한다 — SCARD와 SADD를 따로 부르면 두 실행이 같은 빈자리를 보고 둘 다 앉는다(check-then-act).
-- TTL(EXPIRE)을 걸지 않는다. 남은 token은 사람이 확인한 뒤 지운다(D-5).
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
    return 1
end
if redis.call('SCARD', KEYS[1]) < tonumber(ARGV[2]) then
    redis.call('SADD', KEYS[1], ARGV[1])
    return 1
end
return 0
