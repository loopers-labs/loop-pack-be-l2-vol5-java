-- 쿠폰 신규 발급 수용 자리 획득. KEYS[1] = 전용 Set, ARGV[1] = 이 실행의 token, ARGV[2] = 수용량 C.
-- 반환 1 = ACQUIRED, 0 = FULL. 그 밖의 결과는 오류 응답이다.
-- 존재 확인·크기 확인·추가를 한 스크립트로 실행해 다른 획득이 사이에 끼지 않게 한다.
-- 입력·키 타입 오류는 첫 쓰기(SADD) 전에 드러나므로 실패한 호출은 Set 을 바꾸지 않는다.
if #KEYS ~= 1 or #ARGV ~= 2 then
  return redis.error_reply('COUPON_ADMISSION_ARGS expected 1 key and 2 args')
end

local token = ARGV[1]
if token == '' then
  return redis.error_reply('COUPON_ADMISSION_ARGS token must not be empty')
end

local capacity = tonumber(ARGV[2])
if capacity == nil or capacity ~= capacity or capacity == math.huge
    or capacity < 1 or capacity % 1 ~= 0 then
  return redis.error_reply('COUPON_ADMISSION_ARGS capacity must be a positive integer')
end

-- 키가 Set 이 아니면 SISMEMBER 가 WRONGTYPE 오류로 스크립트를 멈춘다.
-- 같은 token 재확인은 같은 실행의 반복 호출에만 의미가 있으며 점유를 늘리지 않는다.
if redis.call('SISMEMBER', KEYS[1], token) == 1 then
  return 1
end

if redis.call('SCARD', KEYS[1]) >= capacity then
  return 0
end

redis.call('SADD', KEYS[1], token)
return 1
