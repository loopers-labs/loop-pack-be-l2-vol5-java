-- 사용자 기록 · 품절 플래그 · 승격 인원 · 한도를 한 번에 읽는다. 나눠 읽으면 그 사이 승격으로 엇갈린다 (8주차 D-125, D-149)
-- KEYS: 1 epoch, 2 meta, 3 users, 4 sold-out, 5 promoted
-- ARGV: 1 userId
-- 돌려주는 값: {'CLOSED'} | {'NONE'} | {'STATE', 저장값, 품절(0/1), 승격 인원, 한도}
if redis.call('EXISTS', KEYS[1]) == 0 then
  return {'CLOSED'}
end
local stored = redis.call('HGET', KEYS[3], ARGV[1])
if not stored then
  return {'NONE'}
end
return {'STATE', stored, redis.call('EXISTS', KEYS[4]), redis.call('GET', KEYS[5]) or '0',
        redis.call('HGET', KEYS[2], 'total-quota')}
