-- 스케줄러 입장 처리. ARGV = capacity, admissionDurationMillis, soldOut(1/0), canAdmit(1/0).
-- PROCESSING 은 DB 종료 확인 전이라 기한이 지나도 회수하지 않는다.
-- 대상을 모두 검증한 뒤에 쓰며, 검증 실패면 아무것도 쓰지 않는다.
if #KEYS ~= 5 or #ARGV ~= 4 then
  return arg_error('processAdmissions expects 5 keys and 4 args')
end
local capacity = positive_int(ARGV[1], MAX_EXACT)
local duration = positive_int(ARGV[2], MAX_EXACT)
if capacity == nil then
  return arg_error('capacity')
end
if duration == nil then
  return arg_error('admissionDurationMillis')
end
if (ARGV[3] ~= '1' and ARGV[3] ~= '0') or (ARGV[4] ~= '1' and ARGV[4] ~= '0') then
  return arg_error('soldOut/canAdmit must be 1 or 0')
end
local db_sold_out, can_admit = ARGV[3] == '1', ARGV[4] == '1'
if not key_types_ok() then
  return invariant('key type')
end
local sold_out = sold_out_flag()
if sold_out == nil then
  return invariant('meta.soldOut')
end

local now = now_millis()
local expires_at = now + duration
if expires_at > MAX_EXACT then
  return arg_error('expiresAt exceeds exact integer range')
end

local ready_count = redis.call('ZCARD', READY)
local processing_count = redis.call('SCARD', PROCESSING)
if ready_count + processing_count > capacity then
  return invariant('occupancy exceeds capacity')
end

local function load(user, expected)
  local raw = redis.call('HGET', ENTRIES, user)
  if not raw then
    return nil, expected .. ' member without entry'
  end
  local entry, reason = decode_entry(raw)
  if not entry then
    return nil, reason
  end
  local mismatch = index_mismatch(user, entry)
  if mismatch then
    return nil, mismatch
  end
  if entry.status ~= expected then
    return nil, expected .. ' status'
  end
  return entry
end

local expired = {}
for _, user in ipairs(redis.call('ZRANGEBYSCORE', READY, '-inf', int_str(now), 'LIMIT', 0, int_str(capacity))) do
  local entry, reason = load(user, 'READY')
  if not entry then
    return invariant(reason)
  end
  table.insert(expired, {user, entry})
end

local admitted = {}
if can_admit and not db_sold_out and not sold_out then
  local free = capacity - (ready_count - #expired) - processing_count
  if free > 0 then
    for _, user in ipairs(redis.call('ZRANGE', QUEUE, 0, free - 1)) do
      local entry, reason = load(user, 'WAITING')
      if not entry then
        return invariant(reason)
      end
      table.insert(admitted, {user, entry})
    end
  end
end

for _, item in ipairs(expired) do
  item[2].status = 'EXPIRED'
  redis.call('ZREM', READY, item[1])
  redis.call('HSET', ENTRIES, item[1], cjson.encode(item[2]))
end
if db_sold_out then
  redis.call('HSET', META, 'soldOut', '1')
end
for _, item in ipairs(admitted) do
  item[2].status = 'READY'
  item[2].expiresAtMillis = int_str(expires_at)
  redis.call('ZREM', QUEUE, item[1])
  redis.call('HSET', ENTRIES, item[1], cjson.encode(item[2]))
  redis.call('ZADD', READY, int_str(expires_at), item[1])
end
return {'OK', int_str(#expired), int_str(#admitted)}
