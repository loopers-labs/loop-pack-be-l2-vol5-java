-- KEYS: request hash, processing ownership hash, campaign+user index
-- ARGV: request id, unique owner, campaignId:userId
-- Results are kept in MySQL, not in this queue. Delete only the completed
-- execution's records; a new request for the same user must remain untouched.
local function has_type(key, expected)
    local actual = redis.call('TYPE', key).ok
    return actual == 'none' or actual == expected
end
if not has_type(KEYS[1], 'hash') or not has_type(KEYS[2], 'hash') or not has_type(KEYS[3], 'hash') then
    return redis.error_reply('invalid coupon queue data type')
end
local raw = redis.call('HGET', KEYS[1], ARGV[1])
local owner = redis.call('HGET', KEYS[2], ARGV[1])
local indexed = redis.call('HGET', KEYS[3], ARGV[3])
if not raw then
    -- Safe idempotent retry when the original completion was applied. Never
    -- delete a newer request's index. Partial state still needs investigation.
    if not owner and indexed ~= ARGV[1] then
        return 1
    end
    return -1
end
local request = cjson.decode(raw)
if request.status ~= 'PROCESSING' or request.owner ~= ARGV[2] or owner ~= ARGV[2]
        or request.campaignId .. ':' .. request.userId ~= ARGV[3] or indexed ~= ARGV[1] then
    return -1
end
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[3])
return 1
