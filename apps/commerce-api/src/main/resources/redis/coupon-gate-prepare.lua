-- DB 기준 값으로 판단 정보를 새로 채운다.
-- KEYS[1] = 판단 정보(hash), KEYS[2] = 사용자 상태(hash). 이미 발급된 사용자는 "A"(DB 발급 확인)로 둔다.
-- ARGV[1] = 남은 수, ARGV[2] = 종료 시각(epoch ms), ARGV[3] = 키가 사라질 시각(epoch ms), ARGV[4..] = 이미 발급된 사용자 ID
redis.call('DEL', KEYS[1], KEYS[2])
-- pending: 저장 전 예약 수. 준비는 발급 실행이 없을 때만 하므로 0에서 시작한다.
redis.call('HSET', KEYS[1], 'remaining', ARGV[1], 'pending', 0, 'endAt', ARGV[2], 'expireAt', ARGV[3])
-- 한 명령에 넘기는 인자 수를 제한해 나눠 넣는다.
for i = 4, #ARGV, 500 do
  local fields = {}
  for j = i, math.min(i + 499, #ARGV) do
    fields[#fields + 1] = ARGV[j]
    fields[#fields + 1] = 'A'
  end
  redis.call('HSET', KEYS[2], unpack(fields))
end
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
redis.call('PEXPIREAT', KEYS[2], ARGV[3])
return 1
