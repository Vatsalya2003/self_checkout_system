package com.selfcheckout.self_checkout.ratelimit;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

// RATE-LIMIT library: how long each limiter check took, in coarse buckets.
// Cheap enough to record on every request (one array increment), and enough
// to answer "what does the limiter add to a request" as p50 / p99 upper bounds.
public class LatencyHistogram {

    /** Upper bound of each bucket, in microseconds. The last one catches everything slower. */
    static final long[] BOUNDS_MICROS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, Long.MAX_VALUE};

    private final AtomicLongArray counts = new AtomicLongArray(BOUNDS_MICROS.length);
    private final LongAdder totalNanos = new LongAdder();

    public void record(long nanos) {
        long micros = nanos / 1_000;
        int i = 0;
        while (micros > BOUNDS_MICROS[i]) {
            i++;
        }
        counts.incrementAndGet(i);
        totalNanos.add(nanos);
    }

    public Snapshot snapshot() {
        long[] c = new long[BOUNDS_MICROS.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = counts.get(i);
        }
        return new Snapshot(c, totalNanos.sum());
    }

    /** Counts at one moment; two snapshots can be subtracted to get one interval. */
    public record Snapshot(long[] counts, long totalNanos) {

        public long count() {
            long n = 0;
            for (long c : counts) {
                n += c;
            }
            return n;
        }

        public Snapshot minus(Snapshot earlier) {
            long[] c = new long[counts.length];
            for (int i = 0; i < c.length; i++) {
                c[i] = counts[i] - earlier.counts[i];
            }
            return new Snapshot(c, totalNanos - earlier.totalNanos);
        }

        /** Upper bound (microseconds) of the bucket holding the p-th percentile; 0 if empty. */
        public long percentileMicros(double p) {
            long n = count();
            if (n == 0) {
                return 0;
            }
            long rank = (long) Math.ceil(p * n);
            long seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen >= rank) {
                    return BOUNDS_MICROS[i] == Long.MAX_VALUE ? BOUNDS_MICROS[i - 1] : BOUNDS_MICROS[i];
                }
            }
            return BOUNDS_MICROS[counts.length - 2];
        }

        public double meanMicros() {
            long n = count();
            return n == 0 ? 0 : totalNanos / 1_000.0 / n;
        }
    }
}
