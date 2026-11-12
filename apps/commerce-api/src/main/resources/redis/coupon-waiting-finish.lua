-- 발급 실행 종료 기록. ARGV = userId, waitingId, 결과(ISSUED|FAILED). 반환 [APPLIED|UNCHANGED|INVARIANT_ERROR].
-- DB 종료 확인은 호출자 전제다. 현재 waitingId·PROCESSING 이 아니면(반복·옛 호출·새 등록 뒤) 아무것도 바꾸지 않는다.
-- 맞는데 processing 점유가 없으면 UNCHANGED 로 숨기지 않고 INVARIANT_ERROR 다. 자기 점유만 반환하며 재시도·이력은 없다.
if #KEYS ~= 5 or #ARGV ~= 3 then
  return arg_error('finish expects 5 keys and 3 args')
end
local user, waiting_id, outcome = ARGV[1], ARGV[2], ARGV[3]
if not valid_user(user) then
  return arg_error('userId')
end
if waiting_id == '' then
  return arg_error('waitingId')
end
if outcome ~= 'ISSUED' and outcome ~= 'FAILED' then
  return arg_error('outcome must be ISSUED or FAILED')
end
if not key_types_ok() then
  return {'INVARIANT_ERROR'}
end

local raw = redis.call('HGET', ENTRIES, user)
if not raw then
  return {'UNCHANGED'}
end
local entry = decode_entry(raw)
if not entry then
  return {'INVARIANT_ERROR'}
end
if entry.waitingId ~= waiting_id or entry.status ~= 'PROCESSING' then
  return {'UNCHANGED'}
end
if index_mismatch(user, entry) then
  return {'INVARIANT_ERROR'}
end

entry.status = outcome
redis.call('HSET', ENTRIES, user, cjson.encode(entry))
redis.call('SREM', PROCESSING, user)
return {'APPLIED'}
