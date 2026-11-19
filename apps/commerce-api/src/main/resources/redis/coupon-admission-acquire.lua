-- 쿠폰 수용 자리 획득 (8주차 29절 ③). KEYS[1] = 자리 Set, ARGV[1] = capacity, ARGV[2] = token.
-- 확인과 추가를 한 스크립트로 묶어 다른 요청이 사이에 끼지 못하게 한다. 1 = 자리 있음(이미 가진 token 포함), 0 = 자리 없음.
-- TTL은 두지 않는다(반환하지 못한 token은 사람이 정리한다).
local capacity = tonumber(ARGV[1])
local token = ARGV[2]
if capacity == nil or capacity < 1 or capacity ~= math.floor(capacity) then
    return redis.error_reply('capacity must be an integer >= 1')
end
if token == nil or token == '' then
    return redis.error_reply('token must not be empty')
end
if redis.call('SISMEMBER', KEYS[1], token) == 1 then
    return 1
end
if redis.call('SCARD', KEYS[1]) < capacity then
    redis.call('SADD', KEYS[1], token)
    return 1
end
return 0
