-- Apply a committed MySQL cancellation exactly once to activity admission state.
local state = redis.call('HGET', KEYS[3], 'state')
if state == 'CANCELLED' then
    return 1
end
if state ~= 'SUCCESS' or redis.call('HGET', KEYS[3], 'orderNo') ~= ARGV[2] then
    return -1
end
local restore = tonumber(ARGV[3])
if not restore or restore < 0 then
    return -1
end
if restore > 0 then
    if redis.call('EXISTS', KEYS[1]) ~= 1 then
        return -2
    end
    redis.call('INCRBY', KEYS[1], restore)
end
if redis.call('HGET', KEYS[2], ARGV[4]) == ARGV[1] then
    redis.call('HDEL', KEYS[2], ARGV[4])
end
redis.call('HSET', KEYS[3], 'state', 'CANCELLED')
redis.call('ZREM', KEYS[4], ARGV[1])
return 1
