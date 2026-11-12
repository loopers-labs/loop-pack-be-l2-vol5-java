-- 발급 실행 시작. ARGV = userId, waitingId. 반환 [결과명, 항목 JSON(PROCESSING/ISSUED만), ''].
-- 현재 항목의 waitingId 가 같고 기한 전 READY 일 때만 ready 에서 빼고 PROCESSING 으로 바꾼다(STARTED). 자리 수는 그대로다.
-- 기한 연장·품절 검사·만료 회수·다른 사용자 정리는 하지 않는다. 같은 ID 의 동시 시작은 하나만 STARTED 다.
if #KEYS ~= 5 or #ARGV ~= 2 then
  return arg_error('start expects 5 keys and 2 args')
end
local user, waiting_id = ARGV[1], ARGV[2]
if not valid_user(user) then
  return arg_error('userId')
end
if waiting_id == '' then
  return arg_error('waitingId')
end
if not key_types_ok() then
  return invariant('key type')
end

local raw = redis.call('HGET', ENTRIES, user)
if not raw then
  if orphan_index(user) then
    return invariant('index without entry')
  end
  return {'NOT_FOUND', '', ''}
end
local entry, reason = decode_entry(raw)
if not entry then
  return invariant(reason)
end
local mismatch = index_mismatch(user, entry)
if mismatch then
  return invariant(mismatch)
end
if entry.waitingId ~= waiting_id then
  return {'NOT_FOUND', '', ''}
end

local status = entry.status
if status == 'WAITING' then
  return {'NOT_READY', '', ''}
end
if status == 'EXPIRED' then
  return {'EXPIRED', '', ''}
end
if status == 'FAILED' then
  return {'ALREADY_USED', '', ''}
end
if status == 'PROCESSING' or status == 'ISSUED' then
  return {status, view(entry, status), ''}
end
-- 남은 저장 상태는 READY 뿐이다(decode_entry 가 상태 값을 제한한다).
if now_millis() >= tonumber(entry.expiresAtMillis) then
  return {'EXPIRED', '', ''}
end
entry.status = 'PROCESSING'
redis.call('ZREM', READY, user)
redis.call('HSET', ENTRIES, user, cjson.encode(entry))
redis.call('SADD', PROCESSING, user)
return {'STARTED', '', ''}
