-- Idempotent publication of activity metadata and pre-reserved inventory.
-- Existing keys are never overwritten with the database's original quantity.
local stockExists = redis.call('EXISTS', KEYS[1])
local statusExists = redis.call('EXISTS', KEYS[2])
local metaExists = redis.call('EXISTS', KEYS[3])

if stockExists == 1 and statusExists == 1 and metaExists == 1 then
    if redis.call('GET', KEYS[2]) == 'PUBLISHED' then
        return 2 -- safe retry after Redis initialized but before MySQL state update
    end
    return -1
end

if stockExists == 1 or statusExists == 1 or metaExists == 1 then
    return -1 -- partial or unknown state: require reconciliation, never overwrite
end

if ARGV[5] ~= '1' then
    return -2 -- MySQL says this activity was already published; missing Redis state is unsafe
end

local quantity = tonumber(ARGV[1])
if not quantity or quantity <= 0 then
    return -3
end

redis.call('SET', KEYS[1], quantity, 'EX', ARGV[6])
redis.call('SET', KEYS[2], 'PUBLISHED', 'EX', ARGV[6])
redis.call('HSET', KEYS[3], 'status', 'PUBLISHED', 'startsAt', ARGV[2], 'endsAt', ARGV[3],
    'medicineId', ARGV[4], 'perUserLimit', ARGV[7])
redis.call('EXPIRE', KEYS[3], ARGV[6])
return 1
