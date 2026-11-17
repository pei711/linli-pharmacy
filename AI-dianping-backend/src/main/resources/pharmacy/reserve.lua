-- Atomically admit one authenticated user's request to a published pharmacy activity.
-- MySQL intent is written before this script. MySQL remains the final inventory ledger.
-- Return codes: 0 accepted, 1 idempotent replay, 2 user already reserved,
-- 3 sold out, 4 activity unavailable, 5 not started, 6 ended, 7 rate limited,
-- 8 invalid request or inconsistent Redis state.
local requestId = ARGV[1]
local userId = ARGV[2]
local quantity = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local expiresAt = tonumber(ARGV[5])

if not requestId or requestId == '' or not userId or userId == '' or
   not quantity or quantity <= 0 or not now or not expiresAt or expiresAt <= now then
    return 8
end

local stockExists = redis.call('EXISTS', KEYS[1])
local statusExists = redis.call('EXISTS', KEYS[2])
local metaExists = redis.call('EXISTS', KEYS[3])
if stockExists ~= 1 or statusExists ~= 1 or metaExists ~= 1 then
    return 4
end

local prior = redis.call('HGET', KEYS[5], 'requestId')
if prior then
    if prior == requestId and redis.call('HGET', KEYS[5], 'userId') == userId then
        return 1
    end
    return 8
end

if redis.call('GET', KEYS[2]) ~= 'PUBLISHED' then
    return 4
end

local startAt = tonumber(redis.call('HGET', KEYS[3], 'startsAt'))
local endAt = tonumber(redis.call('HGET', KEYS[3], 'endsAt'))
local perUserLimit = tonumber(redis.call('HGET', KEYS[3], 'perUserLimit'))
if not startAt or not endAt or not perUserLimit then
    return 8
end
if now < startAt then
    return 5
end
if now >= endAt then
    return 6
end
if quantity > perUserLimit then
    return 8
end

local already = redis.call('HGET', KEYS[4], userId)
if already then
    if already == requestId then
        return 1
    end
    return 2
end

local activityCount = redis.call('INCR', KEYS[7])
if activityCount == 1 then redis.call('EXPIRE', KEYS[7], ARGV[7]) end
local ipCount = redis.call('INCR', KEYS[8])
if ipCount == 1 then redis.call('EXPIRE', KEYS[8], ARGV[7]) end
local userCount = redis.call('INCR', KEYS[9])
if userCount == 1 then redis.call('EXPIRE', KEYS[9], ARGV[7]) end
if activityCount > tonumber(ARGV[8]) or ipCount > tonumber(ARGV[9]) or userCount > tonumber(ARGV[10]) then
    return 7
end

local available = tonumber(redis.call('GET', KEYS[1]))
if not available or available < quantity then
    return 3
end

redis.call('DECRBY', KEYS[1], quantity)
redis.call('HSET', KEYS[4], userId, requestId)
redis.call('HSET', KEYS[5], 'requestId', requestId, 'activityId', ARGV[6], 'userId', userId,
    'quantity', quantity, 'state', 'PROCESSING', 'expiresAt', expiresAt)
redis.call('EXPIRE', KEYS[4], ARGV[11])
redis.call('EXPIRE', KEYS[5], ARGV[11])
redis.call('ZADD', KEYS[6], expiresAt, requestId)
redis.call('EXPIRE', KEYS[6], ARGV[11])
return 0
