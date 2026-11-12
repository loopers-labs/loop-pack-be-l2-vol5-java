-- 대기 상태 조회(읽기 전용). ARGV = userId. 반환 [FOUND|NOT_FOUND|INVARIANT_ERROR, 항목 JSON, position].
-- 품절 표시가 있으면 WAITING/READY 를 SOLD_OUT 으로, 그 밖의 기한 지난 READY 는 EXPIRED 로 보여 준다.
-- PROCESSING 은 품절이어도 그대로다. 회수·승격·기한 연장은 하지 않는다.
if #KEYS ~= 5 or #ARGV ~= 1 then
  return arg_error('find expects 5 keys and 1 arg')
end
local user = ARGV[1]
if not valid_user(user) then
  return arg_error('userId')
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
local sold_out = sold_out_flag()
if sold_out == nil then
  return invariant('meta.soldOut')
end

local status = entry.status
if (status == 'WAITING' or status == 'READY') and sold_out then
  return {'FOUND', view(entry, 'SOLD_OUT'), ''}
end
if status == 'WAITING' then
  return {'FOUND', view(entry, status), int_str(redis.call('ZRANK', QUEUE, user) + 1)}
end
if status == 'READY' and now_millis() >= tonumber(entry.expiresAtMillis) then
  return {'FOUND', view(entry, 'EXPIRED'), ''}
end
return {'FOUND', view(entry, status), ''}
