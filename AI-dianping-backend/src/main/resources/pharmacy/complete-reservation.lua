-- Mark a durable MySQL order result in Redis without changing the stock counter.
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'SUCCESS' then
    return 1
end
if state ~= 'PROCESSING' or redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
    return -1
end
redis.call('HSET', KEYS[1], 'state', 'SUCCESS', 'orderNo', ARGV[2])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
