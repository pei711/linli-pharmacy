-- Restore one timed-out Redis reservation exactly once after MySQL has transitioned it to EXPIRED.
local state = redis.call('HGET', KEYS[5], 'state')
if state == 'EXPIRED' or state == 'RELEASED' then
    return 1
end
if state ~= 'PROCESSING' then
    return -1
end

local requestId = redis.call('HGET', KEYS[5], 'requestId')
local userId = redis.call('HGET', KEYS[5], 'userId')
local quantity = tonumber(redis.call('HGET', KEYS[5], 'quantity'))
if requestId ~= ARGV[1] or userId ~= ARGV[2] or not quantity or quantity <= 0 then
    return -1
end
if redis.call('EXISTS', KEYS[1]) ~= 1 then
    return -2
end

if redis.call('HGET', KEYS[4], userId) == requestId then
    redis.call('INCRBY', KEYS[1], quantity)
    redis.call('HDEL', KEYS[4], userId)
end
redis.call('HSET', KEYS[5], 'state', 'EXPIRED')
redis.call('ZREM', KEYS[6], requestId)
return 1
