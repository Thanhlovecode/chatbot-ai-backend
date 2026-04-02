local dailyKey    = KEYS[1]
local dailyLimit  = tonumber(ARGV[1])
local tokensToAdd = tonumber(ARGV[2])
local dailyTTL    = tonumber(ARGV[3])

local dailyUsed = tonumber(redis.call('GET', dailyKey) or '0')

if dailyUsed + tokensToAdd > dailyLimit then
    return {0, dailyUsed, dailyLimit}
end

-- Increment and set TTL on first write
local newUsed = tonumber(redis.call('INCRBY', dailyKey, tokensToAdd))
if newUsed == tokensToAdd then
    redis.call('EXPIRE', dailyKey, dailyTTL)
end

return {1, newUsed, dailyLimit}
