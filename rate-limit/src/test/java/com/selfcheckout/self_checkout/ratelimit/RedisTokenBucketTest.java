package com.selfcheckout.self_checkout.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// token-bucket.lua against a real Redis (localhost:6379, database 15 so it
// never touches the services' keys). Skipped, not failed, if Redis is not running.
class RedisTokenBucketTest {

    private static LettuceConnectionFactory first;
    private static LettuceConnectionFactory second;
    private static StringRedisTemplate redis;
    private static RedisTokenBucket bucket;
    private static RedisTokenBucket otherProcess;

    private static LettuceConnectionFactory connect() {
        RedisStandaloneConfiguration c = new RedisStandaloneConfiguration("localhost", 6379);
        c.setDatabase(15);
        LettuceConnectionFactory f = new LettuceConnectionFactory(c);
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    @BeforeAll
    static void setUp() {
        try {
            first = connect();
            second = connect();
            redis = new StringRedisTemplate(first);
            redis.afterPropertiesSet();
            redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis is not running on localhost:6379: " + e.getMessage());
        }
        bucket = new RedisTokenBucket(redis);
        StringRedisTemplate other = new StringRedisTemplate(second);
        other.afterPropertiesSet();
        otherProcess = new RedisTokenBucket(other);
    }

    @AfterAll
    static void tearDown() {
        if (first != null) {
            first.destroy();
        }
        if (second != null) {
            second.destroy();
        }
    }

    private static String newKey() {
        return "rl:test:" + UUID.randomUUID();
    }

    @Test
    void exactlyBurstRequestsGetThroughAtOnce() {
        String key = newKey();
        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            if (bucket.tryAcquire(key, 0.001, 5).allowed()) {   // 0.001/s: no refill during the test
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void aRefusalSaysWhenTheNextTokenComesAndTheTokenDoesCome() throws Exception {
        String key = newKey();
        assertThat(bucket.tryAcquire(key, 100, 1).allowed()).isTrue();
        BucketResult refused = bucket.tryAcquire(key, 100, 1);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMicros()).isBetween(1L, 10_000L);   // 100/s = one every 10 ms
        Thread.sleep(15);
        assertThat(bucket.tryAcquire(key, 100, 1).allowed()).isTrue();
    }

    @Test
    void anIdleBucketRefillsOnlyUpToTheBurst() throws Exception {
        String key = newKey();
        // 20/s = one token every 50 ms, slow next to the ~1 ms each call takes,
        // so the loop below cannot pick up more than one fresh token.
        bucket.tryAcquire(key, 20, 3);
        Thread.sleep(500);   // 10 tokens' worth of time
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryAcquire(key, 20, 3).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(3, 4);   // 3, or 4 if a token arrives during the loop
    }

    @Test
    void anIdleKeyExpiresOnceItsBucketWouldBeFullAgain() throws Exception {
        String key = newKey();
        BucketResult r = bucket.tryAcquire(key, 10, 2);   // one token short: full again in 100 ms
        assertThat(r.fullInMicros()).isEqualTo(100_000);
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(900L, 1_100L);   // 100 ms + 1 s of slack
        Thread.sleep(1_300);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void twoConnectionsLikeTwoProcessesShareOneLimit() {
        String key = newKey();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            RedisTokenBucket who = (i % 2 == 0) ? bucket : otherProcess;
            if (who.tryAcquire(key, 0.001, 10).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(10);   // not 10 each
    }

    @Test
    void manyThreadsAtOnceNeverTakeMoreThanTheBucketHolds() throws Exception {
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            RedisTokenBucket who = (i % 2 == 0) ? bucket : otherProcess;
            results.add(pool.submit(() -> who.tryAcquire(key, 0.001, 100).allowed()));
        }
        int allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                allowed++;
            }
        }
        pool.shutdown();
        assertThat(allowed).isEqualTo(100);
    }
}
