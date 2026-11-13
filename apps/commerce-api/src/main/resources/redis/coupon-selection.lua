-- The hash stores campaign metadata and one field per selected user. A user's
-- value is its exact pending stream ID, or "committed" after a known MySQL commit.
local state, work = KEYS[1], KEYS[2]
local op, epoch, arg = ARGV[1], ARGV[2], ARGV[3]
local stateType = redis.call('TYPE', state).ok
local workType = redis.call('TYPE', work).ok
if (stateType ~= 'none' and stateType ~= 'hash') or (workType ~= 'none' and workType ~= 'stream') then
    return 'UNAVAILABLE'
end

local workSize = redis.call('XLEN', work)
local capacity, occupied, generation, active
if stateType == 'hash' then
    generation = redis.call('HGET', state, '@epoch')
    capacity = tonumber(redis.call('HGET', state, '@quota'))
    active = redis.call('HGET', state, '@active')
    occupied = redis.call('HLEN', state) - 5
    local storedCount = tonumber(redis.call('HGET', state, '@occupied'))
    local pendingCount = tonumber(redis.call('HGET', state, '@pending'))
    if not generation or not capacity or capacity < occupied or occupied < workSize or pendingCount ~= workSize
            or storedCount ~= occupied or (active ~= '0' and active ~= '1') then
        return 'UNAVAILABLE'
    end
elseif op == 'open' and workSize == 0 then
    generation, capacity, occupied = epoch, tonumber(arg), 0
else
    return 'UNAVAILABLE'
end

local function isUsersRecord(record, userId)
    return record and #record[2] == 2 and record[2][1] == 'userId' and record[2][2] == userId
end

local function isPendingRecord(userId, recordId)
    if not string.match(recordId, '^%d+%-%d+$') then return false end
    return isUsersRecord(redis.call('XRANGE', work, recordId, recordId)[1], userId)
end

if op == 'open' then
    -- Explicit reconciliation is the only way to initialize. Merge evidence
    -- before opening; a DB snapshot alone cannot discard surviving Redis work.
    if capacity ~= tonumber(arg) then return 'UNAVAILABLE' end
    local seen, newlyCommitted, knownCommitted = {}, 0, 0
    for i = 5, #ARGV do
        local userId = ARGV[i]
        if seen[userId] then return 'UNAVAILABLE' end
        seen[userId] = true
        local old = redis.call('HGET', state, userId)
        if not old then
            newlyCommitted = newlyCommitted + 1
        elseif old == 'committed' then
            knownCommitted = knownCommitted + 1
        elseif not isPendingRecord(userId, old) then
            return 'UNAVAILABLE'
        end
    end
    if occupied + newlyCommitted > capacity or knownCommitted ~= occupied - workSize then return 'UNAVAILABLE' end
    for _, record in ipairs(redis.call('XRANGE', work, '-', '+')) do
        local userId = record[2][2]
        if not isUsersRecord(record, userId) or redis.call('HGET', state, userId) ~= record[1] then
            return 'UNAVAILABLE'
        end
    end
    for i = 5, #ARGV do
        local userId = ARGV[i]
        local old = redis.call('HGET', state, userId)
        if old and old ~= 'committed' then redis.call('XDEL', work, old) end
        redis.call('HSET', state, userId, 'committed')
    end
    redis.call('HSET', state, '@quota', arg, '@epoch', generation, '@active', ARGV[4],
        '@pending', redis.call('XLEN', work), '@occupied', occupied + newlyCommitted)
    return generation
end

if generation ~= epoch then return 'UNAVAILABLE' end
if op == 'pending' then
    local users = {}
    for _, record in ipairs(redis.call('XRANGE', work, '-', '+', 'COUNT', arg)) do
        local userId = record[2][2]
        if not isUsersRecord(record, userId) or redis.call('HGET', state, userId) ~= record[1] then
            return 'UNAVAILABLE'
        end
        table.insert(users, userId)
    end
    return table.concat(users, ',')
end

local old = redis.call('HGET', state, arg)
if old and old ~= 'committed' and not isPendingRecord(arg, old) then return 'UNAVAILABLE' end
if op == 'select' then
    if old then return 'EXISTING' end
    if active == '0' then return 'INACTIVE' end
    if occupied >= capacity then return 'FULL' end
    local recordId = redis.call('XADD', work, '*', 'userId', arg)
    redis.call('HSET', state, arg, recordId, '@pending', workSize + 1, '@occupied', occupied + 1)
    return 'NEW'
end

if op == 'complete' and old then
    if old ~= 'committed' then
        redis.call('XDEL', work, old)
        redis.call('HSET', state, arg, 'committed', '@pending', workSize - 1)
    end
    return 'OK'
end
return 'UNAVAILABLE'
