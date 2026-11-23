package com.selfcheckout.self_checkout.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

// RATE-LIMIT library: decides, for one request that matches a rule, whether it
// may go through. In order:
//
//   1. Deny cache. If this process already learned from Redis that the bucket
//      is empty until time T, refuse straight away (no Redis call). Without
//      this, the course load client -- which retries a refused request
//      immediately, in a loop -- would send Redis one call per 429. The cache
//      can never admit too much: nothing can ADD tokens to the bucket before T,
//      other processes can only take them. The trade-off is the other way:
//      T is measured on Redis's clock and reaches us one round trip late, so a
//      process may wait up to that round trip (~0.1 ms) longer than strictly
//      needed. The accuracy measurement shows how much that costs.
//   2. Redis, through the circuit breaker: the shared bucket, one round trip.
//   3. Local fallback, when Redis failed or the breaker is open: this process's
//      own bucket at rate / fallback-instances. Approximate, not shared, but
//      the services stay protected and Redis being down never causes an error.
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Bound on the per-key maps (deny cache, fallback buckets) so they can never grow without limit. */
    private static final int MAX_LOCAL_KEYS = 10_000;

    private final RateLimitProperties props;
    private final BucketStore store;
    private final LongSupplier nanoClock;
    private final CircuitBreaker breaker;
    private final AtomicBoolean enabled;

    private final Map<String, RuleStats> stats = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Long> deniedUntilNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalTokenBucket> fallbackBuckets = new ConcurrentHashMap<>();
    private final LatencyHistogram checkTime = new LatencyHistogram();
    private final LongAdder redisErrors = new LongAdder();
    private volatile String lastRedisError = "";

    // what the summary line printed last time, so it can print the difference
    private final Map<String, long[]> lastLogged = new ConcurrentHashMap<>();
    private LatencyHistogram.Snapshot lastLoggedTime = new LatencyHistogram().snapshot();

    public RateLimiter(RateLimitProperties props, BucketStore store, LongSupplier nanoClock) {
        this.props = props;
        this.store = store;
        this.nanoClock = nanoClock;
        this.enabled = new AtomicBoolean(props.isEnabled());
        this.breaker = new CircuitBreaker(props.getBreakerFailureThreshold(), props.getBreakerOpenFor().toNanos(),
                nanoClock, this::onBreakerChange);
        for (RateLimitProperties.Rule rule : props.getRules()) {
            stats.put(rule.getName(), new RuleStats());
        }
    }

    /** The rule for this request, or null if no rule applies (then the request is never checked). */
    public RateLimitProperties.Rule match(String method, String path) {
        for (RateLimitProperties.Rule rule : props.getRules()) {
            if (rule.matches(method, path)) {
                return rule;
            }
        }
        return null;
    }

    public Decision check(RateLimitProperties.Rule rule, String key) {
        long start = nanoClock.getAsLong();
        RuleStats s = stats.get(rule.getName());
        String bucketKey = "rl:" + rule.getName() + ":" + key;
        try {
            // 1. deny cache
            if (props.isDenyCache()) {
                Long until = deniedUntilNanos.get(bucketKey);
                if (until != null) {
                    long left = until - start;
                    if (left > 0) {
                        s.rejected.increment();
                        s.rejectedFromCache.increment();
                        long fullIn = (long) Math.ceil(rule.getBurst() * 1_000_000 / rule.getRatePerSecond());
                        return new Decision(false, rule, 0, (left + 999) / 1_000, fullIn, Decision.Source.DENY_CACHE);
                    }
                    deniedUntilNanos.remove(bucketKey, until);
                }
            }

            // 2. Redis, unless the breaker says it is down
            if (breaker.allowRequest()) {
                try {
                    BucketResult r = store.tryAcquire(bucketKey, rule.getRatePerSecond(), rule.getBurst());
                    breaker.recordSuccess();
                    if (!r.allowed() && props.isDenyCache()) {
                        if (deniedUntilNanos.size() > MAX_LOCAL_KEYS) {
                            deniedUntilNanos.clear();
                        }
                        deniedUntilNanos.put(bucketKey, start + r.retryAfterMicros() * 1_000);
                    }
                    return count(s, rule, r, Decision.Source.REDIS);
                } catch (RuntimeException e) {
                    redisErrors.increment();
                    lastRedisError = describe(e);
                    breaker.recordFailure(lastRedisError);
                }
            }

            // 3. local fallback
            if (fallbackBuckets.size() > MAX_LOCAL_KEYS) {
                fallbackBuckets.clear();
            }
            int instances = Math.max(1, props.getFallbackInstances());
            LocalTokenBucket local = fallbackBuckets.computeIfAbsent(bucketKey, k -> new LocalTokenBucket(
                    rule.getRatePerSecond() / instances, Math.max(1, rule.getBurst() / instances), nanoClock.getAsLong()));
            s.fallback.increment();
            return count(s, rule, local.tryAcquire(nanoClock.getAsLong()), Decision.Source.LOCAL_FALLBACK);
        } finally {
            checkTime.record(nanoClock.getAsLong() - start);
        }
    }

    private static Decision count(RuleStats s, RateLimitProperties.Rule rule, BucketResult r, Decision.Source source) {
        (r.allowed() ? s.allowed : s.rejected).increment();
        return new Decision(r.allowed(), rule, r.remaining(), r.retryAfterMicros(), r.fullInMicros(), source);
    }

    // ---- on/off, state and statistics --------------------------------------

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean on) {
        if (enabled.getAndSet(on) != on) {
            log.info("ratelimit | limiter turned {} at runtime", on ? "ON" : "OFF");
        }
    }

    /** "UP" while the breaker lets calls through to Redis, "DOWN" while the local fallback is in use. */
    public String redisStatus() {
        return breaker.state() == CircuitBreaker.State.CLOSED ? "UP" : "DOWN";
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", isEnabled());
        out.put("redis", redisStatus());
        out.put("breaker", breaker.state().name());
        out.put("redisErrors", redisErrors.sum());
        out.put("lastRedisError", lastRedisError);
        out.put("fallbackInstances", props.getFallbackInstances());
        LatencyHistogram.Snapshot t = checkTime.snapshot();
        out.put("checks", t.count());
        out.put("checkMeanMicros", Math.round(t.meanMicros() * 10) / 10.0);
        out.put("checkP50MicrosAtMost", t.percentileMicros(0.50));
        out.put("checkP99MicrosAtMost", t.percentileMicros(0.99));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RateLimitProperties.Rule rule : props.getRules()) {
            RuleStats s = stats.get(rule.getName());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", rule.getName());
            r.put("method", rule.getMethod());
            r.put("path", rule.getPath());
            r.put("key", rule.getKey().name().toLowerCase());
            r.put("ratePerSecond", rule.getRatePerSecond());
            r.put("burst", rule.getBurst());
            r.put("allowed", s.allowed.sum());
            r.put("rejected", s.rejected.sum());
            r.put("rejectedWithoutRedisCall", s.rejectedFromCache.sum());
            r.put("decidedByLocalFallback", s.fallback.sum());
            rules.add(r);
        }
        out.put("rules", rules);
        return out;
    }

    /** One line per rule with what happened since the last call; nothing if nothing happened. */
    public void logSummary() {
        LatencyHistogram.Snapshot now = checkTime.snapshot();
        LatencyHistogram.Snapshot interval = now.minus(lastLoggedTime);
        lastLoggedTime = now;
        for (RateLimitProperties.Rule rule : props.getRules()) {
            RuleStats s = stats.get(rule.getName());
            long[] cur = {s.allowed.sum(), s.rejected.sum(), s.rejectedFromCache.sum(), s.fallback.sum()};
            long[] prev = lastLogged.getOrDefault(rule.getName(), new long[4]);
            lastLogged.put(rule.getName(), cur);
            long allowed = cur[0] - prev[0];
            long rejected = cur[1] - prev[1];
            if (allowed + rejected == 0) {
                continue;
            }
            log.info("ratelimit | rule={} key={} allowed={} rejected={} (no Redis call: {}) fallback={} redis={} "
                            + "check p50<={}us p99<={}us | last {}s",
                    rule.getName(), rule.getKey().name().toLowerCase(), allowed, rejected, cur[2] - prev[2],
                    cur[3] - prev[3], redisStatus(), interval.percentileMicros(0.50), interval.percentileMicros(0.99),
                    props.getLogInterval().toSeconds());
        }
    }

    public void logRules() {
        if (props.getRules().isEmpty()) {
            log.info("ratelimit | no rules configured: every request passes unchecked");
            return;
        }
        for (RateLimitProperties.Rule rule : props.getRules()) {
            log.info("ratelimit | rule {} | enabled={} | fallback when Redis is down: rate / {} per process",
                    rule, isEnabled(), Math.max(1, props.getFallbackInstances()));
        }
    }

    private void onBreakerChange(CircuitBreaker.State from, CircuitBreaker.State to, String reason) {
        if (to == CircuitBreaker.State.OPEN && from == CircuitBreaker.State.CLOSED) {
            log.warn("ratelimit | redis=DOWN ({}) -> local fallback, each process allows rate / {} on its own; "
                    + "retrying Redis every {} ms", reason, Math.max(1, props.getFallbackInstances()),
                    props.getBreakerOpenFor().toMillis());
        } else if (to == CircuitBreaker.State.CLOSED) {
            log.info("ratelimit | redis=UP again -> shared limit restored");
            fallbackBuckets.clear();
        }
        // OPEN -> HALF_OPEN -> OPEN repeats every second while Redis stays down;
        // not logged, so the log stays readable (the summary line shows redis=DOWN).
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? "" : ": " + root.getMessage();
        return root.getClass().getSimpleName() + msg;
    }

    static String formatRate(double rate) {
        return rate == Math.rint(rate) ? Long.toString((long) rate) : Double.toString(rate);
    }

    private static final class RuleStats {
        final LongAdder allowed = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder rejectedFromCache = new LongAdder();
        final LongAdder fallback = new LongAdder();
    }
}
