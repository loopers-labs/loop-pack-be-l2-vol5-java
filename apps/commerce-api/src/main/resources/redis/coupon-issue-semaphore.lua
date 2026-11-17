-- The Set contains execution UUIDs. The persistent hash detects a lost Set
-- (including loss of just one member), a different capacity, or a replaced generation.
local state, tokens = KEYS[1], KEYS[2]
local op, epoch, capacity, owner = ARGV[1], ARGV[2], tonumber(ARGV[3]), ARGV[4]
if op == 'recover-one' and (#KEYS ~= 2 or #ARGV ~= 5) then return false end
-- A recovery command applies to one evidenced execution; a malformed command makes no changes.
local stateType = redis.call('TYPE', state).ok
local tokenType = redis.call('TYPE', tokens).ok
if (stateType ~= 'none' and stateType ~= 'hash') or (tokenType ~= 'none' and tokenType ~= 'set')
        or not capacity or capacity < 1 or not owner or redis.call('PTTL', state) >= 0 or redis.call('PTTL', tokens) >= 0 then
    return false
end
local held = redis.call('SCARD', tokens)
local generation = redis.call('HGET', state, 'epoch')
if stateType == 'hash' then
    if not generation or tonumber(redis.call('HGET', state, 'capacity')) ~= capacity
            or tonumber(redis.call('HGET', state, 'held')) ~= held or held > capacity
            or redis.call('HLEN', state) ~= 3 + held then
        return false
    end
elseif op ~= 'open' or held ~= 0 then
    return false
end

if op == 'open' then
    -- The caller supplies only its own tokens whose executions are known to have ended.
    -- Validate all surviving members before removing any; never clear the Set wholesale.
    local owned = 0
    for i = 5, #ARGV do
        if redis.call('SISMEMBER', tokens, ARGV[i]) == 1 then
            if redis.call('HGET', state, 'owner:' .. ARGV[i]) ~= owner then return false end
            owned = owned + 1
        end
    end
    if owned ~= held then return false end
    for i = 5, #ARGV do
        redis.call('SREM', tokens, ARGV[i])
        redis.call('HDEL', state, 'owner:' .. ARGV[i])
    end
    generation = generation or epoch
    redis.call('HSET', state, 'epoch', generation, 'capacity', capacity, 'held', 0)
    return generation
end

if generation ~= epoch then return false end
local token = ARGV[5]
local present = redis.call('SISMEMBER', tokens, token)
local ownerField = 'owner:' .. token
local storedOwner = redis.call('HGET', state, ownerField)
if (present == 1 and not storedOwner) or (present == 0 and storedOwner) then return false end
if op == 'confirm' then
    if present == 1 and storedOwner == owner then return 1 end
    return false
end
if op == 'acquire' then
    -- Only the local owner may reconfirm with 'confirm'. A collision did not grant a slot.
    if present == 1 then return -1 end
    if held >= capacity then return 0 end
    redis.call('SADD', tokens, token)
    redis.call('HSET', state, 'held', held + 1, ownerField, owner)
    return 1
end
if op == 'release' or op == 'recover-one' then
    if present == 0 then
        if op == 'recover-one' then return false end
        return 0
    end
    if storedOwner ~= owner then return false end
    redis.call('SREM', tokens, token)
    redis.call('HDEL', state, ownerField)
    redis.call('HSET', state, 'held', held - 1)
    return 1
end
return false
