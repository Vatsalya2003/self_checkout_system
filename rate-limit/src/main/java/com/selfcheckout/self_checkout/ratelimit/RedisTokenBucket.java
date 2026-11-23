package com.selfcheckout.self_checkout.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

// RATE-LIMIT library: the shared bucket. One check = one round trip to Redis
// (EVALSHA of token-bucket.lua; Spring sends the full script once if Redis
// does not know it yet). The script does the whole read-refill-take-write
// atomically, so the limit holds across any number of processes.
public class RedisTokenBucket implements BucketStore {

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("ratelimit/token-bucket.lua")));
        script.setResultType(List.class);
    }

    @Override
    public BucketResult tryAcquire(String key, double ratePerSecond, int burst) {
        @SuppressWarnings("unchecked")
        List<Long> r = redis.execute(script, List.of(key), Double.toString(ratePerSecond), Integer.toString(burst));
        if (r == null || r.size() != 4) {
            throw new IllegalStateException("unexpected reply from token-bucket.lua: " + r);
        }
        return new BucketResult(r.get(0) == 1L, r.get(1), r.get(2), r.get(3));
    }
}
