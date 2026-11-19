-- 202 로 약속한 당첨 표시. DB 에 ticket 을 남긴 뒤 부른다. KEYS = [winners], ARGV = [userId]
if redis.call('HGET', KEYS[1], ARGV[1]) == 'PENDING' then
  redis.call('HSET', KEYS[1], ARGV[1], 'TICKETED')
end
return 'OK'
