-- 대기실 Lua 공통 부분. 단독으로 실행하지 않고, Java 저장소가 각 작업 스크립트 앞에 붙여 한 스크립트로 보낸다.
-- KEYS = meta(Hash), entries(Hash), queue(ZSet), ready(ZSet), processing(Set). 모두 같은 campaignId 의 키다.
-- 반환은 문자열 배열이다. 입력 오류는 error_reply 로, 저장 상태를 설명할 수 없으면 쓰기 전에 INVARIANT_ERROR 로 끝낸다.
-- 어느 쪽이든 복구·삭제 분기는 두지 않는다.
local META, ENTRIES, QUEUE, READY, PROCESSING = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]

-- ZSet score(double)와 Lua number 가 정확히 표현하는 정수 상한 2^53 - 1. 넘는 값은 clamp 하지 않고 오류로 드러낸다.
local MAX_EXACT = 9007199254740991

local function arg_error(message)
  return redis.error_reply('COUPON_WAITING_ARGS ' .. message)
end

local function invariant(reason)
  return {'INVARIANT_ERROR', reason, ''}
end

-- 정수를 지수 표기 없이 십진 문자열로 만든다. 큰 수를 redis.call 인수로 그대로 넘기면 %.14g 로 바뀌어 자릿수를 잃으므로
-- 순번·시각은 항상 이 함수로 바꿔 넘긴다.
local function int_str(n)
  return string.format('%.0f', n)
end

-- 1 이상 limit 이하의 십진 정수 문자열만 받는다. 아니면 nil.
local function positive_int(value, limit)
  if type(value) ~= 'string' or not string.match(value, '^[1-9]%d*$') or #value > 16 then
    return nil
  end
  local n = tonumber(value)
  if n > limit then
    return nil
  end
  return n
end

-- userId 는 숫자로 바꾸지 않고 십진 문자열 그대로 field/member 로 쓴다(Long 전체 범위 보존).
local function valid_user(value)
  return type(value) == 'string' and string.match(value, '^[1-9]%d*$') ~= nil and #value <= 19
end

local function now_millis()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local function key_types_ok()
  local expected = {{META, 'hash'}, {ENTRIES, 'hash'}, {QUEUE, 'zset'}, {READY, 'zset'}, {PROCESSING, 'set'}}
  for _, pair in ipairs(expected) do
    local actual = redis.call('TYPE', pair[1]).ok
    if actual ~= 'none' and actual ~= pair[2] then
      return false
    end
  end
  return true
end

-- 품절 표시는 없음(false) 또는 '1'(true)만 정상이다. 그 밖의 값은 nil.
local function sold_out_flag()
  local value = redis.call('HGET', META, 'soldOut')
  if value == false then
    return false
  end
  if value == '1' then
    return true
  end
  return nil
end

local STORED = {WAITING = true, READY = true, PROCESSING = true, ISSUED = true, EXPIRED = true, FAILED = true}

-- entries 의 JSON 기록을 해석한다. 정수 값은 JSON 숫자 반올림을 피하려고 십진 문자열로 저장한다.
local function decode_entry(raw)
  local ok, entry = pcall(cjson.decode, raw)
  if not ok or type(entry) ~= 'table' then
    return nil, 'entry is not a JSON object'
  end
  if type(entry.waitingId) ~= 'string' or entry.waitingId == '' then
    return nil, 'entry.waitingId'
  end
  if not STORED[entry.status] then
    return nil, 'entry.status'
  end
  if positive_int(entry.sequence, MAX_EXACT) == nil then
    return nil, 'entry.sequence'
  end
  if entry.expiresAtMillis ~= nil and positive_int(entry.expiresAtMillis, MAX_EXACT) == nil then
    return nil, 'entry.expiresAtMillis'
  end
  if (entry.status == 'READY' or entry.status == 'PROCESSING') and entry.expiresAtMillis == nil then
    return nil, 'entry.expiresAtMillis missing'
  end
  return entry
end

-- 저장 상태와 queue/ready/processing 색인이 맞는지 본다. 사용자는 상태에 맞는 색인 하나에만 있어야 한다.
local function index_mismatch(user, entry)
  local queued = redis.call('ZSCORE', QUEUE, user)
  local ready = redis.call('ZSCORE', READY, user)
  local processing = redis.call('SISMEMBER', PROCESSING, user) == 1
  local status = entry.status
  if status == 'WAITING' then
    if queued == false or tonumber(queued) ~= tonumber(entry.sequence) or ready or processing then
      return 'WAITING index'
    end
  elseif status == 'READY' then
    if ready == false or tonumber(ready) ~= tonumber(entry.expiresAtMillis) or queued or processing then
      return 'READY index'
    end
  elseif status == 'PROCESSING' then
    if not processing or queued or ready then
      return 'PROCESSING index'
    end
  elseif queued or ready or processing then
    return status .. ' index'
  end
  return nil
end

-- 기록 없는 사용자가 색인에 남아 있는지 본다.
local function orphan_index(user)
  return redis.call('ZSCORE', QUEUE, user) ~= false or redis.call('ZSCORE', READY, user) ~= false
    or redis.call('SISMEMBER', PROCESSING, user) == 1
end

-- 응답용 JSON. 저장 상태 대신 보여 줄 상태(EXPIRED·SOLD_OUT 표현)를 넣을 수 있다.
local function view(entry, status)
  return cjson.encode({waitingId = entry.waitingId, status = status, sequence = entry.sequence,
    expiresAtMillis = entry.expiresAtMillis})
end
