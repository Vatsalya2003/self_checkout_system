-- Token bucket, checked and updated in ONE atomic step inside Redis.
--
-- Redis runs a script without interleaving any other command, so two
-- processes checking the same key at the same moment can never both take
-- the last token. That is what makes the limit shared: every instance of a
-- service runs this same script against the same key.
--
-- KEYS[1]  the bucket, e.g. rl:new-transactions:global
-- ARGV[1]  refill rate, tokens per second (may be fractional)
-- ARGV[2]  burst, the most tokens the bucket can hold
--
-- The bucket is a hash with two fields:
--   t   tokens left (a float, refilled continuously)
--   ts  when it was last updated, in microseconds of REDIS time
-- Redis's own clock is used, not the caller's, so instances whose clocks
-- disagree still see one consistent bucket.
--
-- Returns {allowed (1/0), whole tokens left, microseconds until the next
-- token (0 if allowed), microseconds until the bucket is full again}.

local key   = KEYS[1]
local rate  = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])

local now_parts = redis.call('TIME')
local now = tonumber(now_parts[1]) * 1000000 + tonumber(now_parts[2])

local state  = redis.call('HMGET', key, 't', 'ts')
local tokens = tonumber(state[1])
local last   = tonumber(state[2])
if tokens == nil or last == nil then
  -- First request for this key (or it expired): start with a full bucket.
  tokens = burst
  last = now
end

-- Refill for the time that passed, but never above the burst size.
local elapsed = now - last
if elapsed < 0 then elapsed = 0 end
tokens = math.min(burst, tokens + elapsed * rate / 1000000)

local allowed = 0
local next_in = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  next_in = math.ceil((1 - tokens) * 1000000 / rate)
end

local full_in = math.ceil((burst - tokens) * 1000000 / rate)

redis.call('HSET', key, 't', tokens, 'ts', now)
-- An idle key disappears once its bucket would be full again (plus a
-- second of slack): a full bucket and a missing key mean the same thing,
-- so nothing is lost and memory stays bounded by the number of ACTIVE keys.
redis.call('PEXPIRE', key, math.ceil(full_in / 1000) + 1000)

return {allowed, math.floor(tokens), next_in, full_in}
