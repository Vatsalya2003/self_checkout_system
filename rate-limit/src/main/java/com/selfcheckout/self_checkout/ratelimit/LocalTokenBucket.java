package com.selfcheckout.self_checkout.ratelimit;

// RATE-LIMIT library: the same token bucket as token-bucket.lua, kept in this
// process's memory. Used only as the FALLBACK while Redis is unreachable, so
// each process still protects itself, just not in step with the others.
//
// The clock is passed in (nanoseconds) so the unit tests can move time
// forward exactly instead of sleeping.
public class LocalTokenBucket {

    private final double ratePerSecond;
    private final int burst;
    private double tokens;
    private long lastNanos;

    public LocalTokenBucket(double ratePerSecond, int burst, long nowNanos) {
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.tokens = burst;   // a new bucket starts full, like a missing Redis key
        this.lastNanos = nowNanos;
    }

    public synchronized BucketResult tryAcquire(long nowNanos) {
        // Two threads can read the clock in one order and get this lock in the
        // other; the later one then brings an EARLIER time. Never move the
        // bucket's clock backwards, or that stretch of time would refill twice.
        long elapsed = Math.max(0, nowNanos - lastNanos);
        tokens = Math.min(burst, tokens + elapsed * ratePerSecond / 1_000_000_000.0);
        lastNanos = Math.max(lastNanos, nowNanos);

        boolean allowed = tokens >= 1;
        long nextIn = 0;
        if (allowed) {
            tokens -= 1;
        } else {
            nextIn = (long) Math.ceil((1 - tokens) * 1_000_000 / ratePerSecond);
        }
        long fullIn = (long) Math.ceil((burst - tokens) * 1_000_000 / ratePerSecond);
        return new BucketResult(allowed, (long) Math.floor(tokens), nextIn, fullIn);
    }
}
