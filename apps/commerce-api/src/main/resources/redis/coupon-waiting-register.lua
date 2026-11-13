-- 대기 등록. ARGV = userId, 새 waitingId 후보.
-- 기한 지난 READY 회수는 스케줄러 몫이라 여기서는 EXPIRED 로 보여 주기만 한다.
if #KEYS ~= 5 or #ARGV ~= 2 then
  return arg_error('register expects 5 keys and 2 args')
end
local user, candidate = ARGV[1], ARGV[2]
if not valid_user(user) then
  return arg_error('userId')
end
if candidate == '' then
  return arg_error('waitingId')
end
if not key_types_ok() then
  return invariant('key type')
end

local raw = redis.call('HGET', ENTRIES, user)
if raw then
  local entry, reason = decode_entry(raw)
  if not entry then
    return invariant(reason)
  end
  local mismatch = index_mismatch(user, entry)
  if mismatch then
    return invariant(mismatch)
  end
  local status = entry.status
  if status == 'WAITING' then
    return {'EXISTING', view(entry, status), int_str(redis.call('ZRANK', QUEUE, user) + 1)}
  end
  if status == 'READY' then
    if now_millis() >= tonumber(entry.expiresAtMillis) then
      return {'EXISTING', view(entry, 'EXPIRED'), ''}
    end
    return {'EXISTING', view(entry, status), ''}
  end
  if status == 'PROCESSING' or status == 'ISSUED' then
    return {'EXISTING', view(entry, status), ''}
  end
elseif orphan_index(user) then
  return invariant('index without entry')
end

local sold_out = sold_out_flag()
if sold_out == nil then
  return invariant('meta.soldOut')
end
if sold_out then
  return {'SOLD_OUT', '', ''}
end

-- 2^53 - 1 을 넘는 순번은 줄 수 없어 쓰기 전에 오류로 끝낸다.
local next_raw = redis.call('HGET', META, 'nextSequence')
local sequence = 1
if next_raw ~= false then
  sequence = positive_int(next_raw, MAX_EXACT + 1)
  if sequence == nil then
    return invariant('meta.nextSequence')
  end
  if sequence > MAX_EXACT then
    return invariant('sequence exhausted')
  end
end

local json = cjson.encode({waitingId = candidate, status = 'WAITING', sequence = int_str(sequence)})
redis.call('HSET', META, 'nextSequence', int_str(sequence + 1))
redis.call('HSET', ENTRIES, user, json)
redis.call('ZADD', QUEUE, int_str(sequence), user)
return {'CREATED', json, int_str(redis.call('ZRANK', QUEUE, user) + 1)}
