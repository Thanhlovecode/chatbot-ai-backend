local key        = KEYS[1]
local capacity   = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local nowMs      = tonumber(ARGV[3])

local data      = redis.call('HMGET', key, 'tokens', 'lastRefillMs')
local tokens    = tonumber(data[1])
local lastRefil = tonumber(data[2])

-- First access: initialize bucket with full capacity
if tokens == nil then
    tokens    = capacity
    lastRefil = nowMs
end

-- Refill: add tokens proportional to elapsed time
local elapsedSec = (nowMs - lastRefil) / 1000.0
local refilled   = math.floor(elapsedSec * refillRate)
tokens = math.min(capacity, tokens + refilled)
if refilled > 0 then lastRefil = nowMs end

if tokens >= 1 then
    -- Consume one token
    tokens = tokens - 1
    redis.call('HSET', key, 'tokens', tokens, 'lastRefillMs', lastRefil)
    -- TTL: keep alive for the full refill period + buffer
    redis.call('EXPIRE', key, math.ceil(capacity / refillRate) + 10)
    return {1, tokens, 0}
else
    -- Not allowed: compute how many seconds until 1 token is available
    local retryAfter = math.ceil((1.0 - tokens) / refillRate)
    return {0, 0, retryAfter}
end
