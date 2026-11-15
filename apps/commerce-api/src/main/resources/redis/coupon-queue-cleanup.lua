-- 다 처리된 작업 큐를 정리한다. 접수가 끝났고(종료 시각 지남), 아직 안 읽은 작업·처리 완료 전 작업·보류 작업이 없을 때만 지운다.
-- KEYS: stock(hash), users(hash), jobs(stream), held(hash). ARGV[1] = 소비자 그룹 이름, ARGV[2] = 지금(epoch ms)
if redis.call('TYPE', KEYS[3]).ok ~= 'stream' then return 0 end
local endAt = tonumber(redis.call('HGET', KEYS[1], 'endAt'))
if endAt and tonumber(ARGV[2]) < endAt then return 0 end
if redis.call('HLEN', KEYS[4]) > 0 then return 0 end
local groups = redis.call('XINFO', 'GROUPS', KEYS[3])
for _, group in ipairs(groups) do
  local info = {}
  for i = 1, #group, 2 do info[group[i]] = group[i + 1] end
  if info['name'] == ARGV[1] then
    if tonumber(info['pending']) ~= 0 then return 0 end
    -- lag을 알 수 없으면(nil) 끝났다고 보지 않는다.
    if info['lag'] == nil or info['lag'] == false or tonumber(info['lag']) ~= 0 then return 0 end
    redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
    return 1
  end
end
return 0
