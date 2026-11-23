package com.selfcheckout.self_checkout.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// The limiter's decisions around the store: deny cache, fallback, breaker.
// "Redis" here is a fake that keeps real token buckets on the test's clock,
// so the numbers are exact.
class RateLimiterTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final AtomicInteger storeCalls = new AtomicInteger();
    private final AtomicBoolean storeDown = new AtomicBoolean();
    private final Map<String, LocalTokenBucket> fakeRedis = new HashMap<>();

    private final BucketStore store = (key, rate, burst) -> {
        storeCalls.incrementAndGet();
        if (storeDown.get()) {
            throw new IllegalStateException("connection refused");
        }
        return fakeRedis.computeIfAbsent(key, k -> new LocalTokenBucket(rate, burst, now.get())).tryAcquire(now.get());
    };

    private RateLimiter limiter(int fallbackInstances) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setName("new-transactions");
        rule.setMethod("POST");
        rule.setPath("/transactions");
        rule.setRatePerSecond(100);
        rule.setBurst(10);
        props.setRules(List.of(rule));
        props.setFallbackInstances(fallbackInstances);
        props.setBreakerFailureThreshold(3);
        props.setBreakerOpenFor(Duration.ofSeconds(1));
        return new RateLimiter(props, store, now::get);
    }

    private static RateLimitProperties.Rule rule(RateLimiter l) {
        return l.match("POST", "/transactions");
    }

    @Test
    void onlyTheConfiguredMethodAndPathMatch() {
        RateLimiter l = limiter(1);
        assertThat(l.match("POST", "/transactions")).isNotNull();
        assertThat(l.match("GET", "/transactions")).isNull();
        assertThat(l.match("POST", "/transactions/tx-1/items")).isNull();   // scans are never limited
        assertThat(l.match("POST", "/transactions/tx-1/complete")).isNull();
    }

    @Test
    void afterARefusalTheDenyCacheAnswersWithoutCallingRedisUntilTheNextToken() {
        RateLimiter l = limiter(1);
        for (int i = 0; i < 10; i++) {
            assertThat(l.check(rule(l), "global").allowed()).isTrue();
        }
        Decision refused = l.check(rule(l), "global");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.source()).isEqualTo(Decision.Source.REDIS);
        int callsSoFar = storeCalls.get();

        // the next token is 10 ms away (100 per second): everything before that is answered locally
        for (int i = 0; i < 1_000; i++) {
            assertThat(l.check(rule(l), "global").source()).isEqualTo(Decision.Source.DENY_CACHE);
        }
        assertThat(storeCalls.get()).isEqualTo(callsSoFar);

        now.addAndGet(10 * MS);
        Decision next = l.check(rule(l), "global");
        assertThat(next.allowed()).isTrue();
        assertThat(next.source()).isEqualTo(Decision.Source.REDIS);
    }

    @Test
    void theDenyCacheDoesNotChangeHowManyAreAdmitted() {
        // 10 simulated seconds of 20,000 attempts per second against 100/s, burst 10
        RateLimiter l = limiter(1);
        int allowed = 0;
        for (long t = 0; t <= 10_000 * MS; t += MS / 20) {
            now.set(t);
            if (l.check(rule(l), "global").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(100 * 10 + 10);
        // and almost none of the 200,000 refusals needed Redis
        assertThat(storeCalls.get()).isLessThan(3_000);
    }

    @Test
    void whenRedisFailsTheRequestIsDecidedLocallyAndNothingIsThrown() {
        RateLimiter l = limiter(1);
        storeDown.set(true);
        Decision d = l.check(rule(l), "global");
        assertThat(d.allowed()).isTrue();
        assertThat(d.source()).isEqualTo(Decision.Source.LOCAL_FALLBACK);
    }

    @Test
    void afterThreeFailuresTheBreakerStopsCallingRedisAndProbesOncePerSecond() {
        RateLimiter l = limiter(1);
        storeDown.set(true);
        for (int i = 0; i < 100; i++) {
            l.check(rule(l), "global");
        }
        assertThat(storeCalls.get()).isEqualTo(3);
        assertThat(l.redisStatus()).isEqualTo("DOWN");

        now.addAndGet(1_000 * MS);
        storeDown.set(false);   // Redis is back
        Decision probe = l.check(rule(l), "global");
        assertThat(probe.source()).isEqualTo(Decision.Source.REDIS);
        assertThat(l.redisStatus()).isEqualTo("UP");
    }

    @Test
    void theFallbackSplitsTheLimitBetweenInstances() {
        RateLimiter l = limiter(2);   // two processes share the limit: each takes half
        storeDown.set(true);
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (l.check(rule(l), "global").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);   // burst 10 / 2
    }

    @Test
    void turnedOffNothingIsChecked() {
        RateLimiter l = limiter(1);
        l.setEnabled(false);
        assertThat(l.isEnabled()).isFalse();
        assertThat(storeCalls.get()).isZero();
    }
}
