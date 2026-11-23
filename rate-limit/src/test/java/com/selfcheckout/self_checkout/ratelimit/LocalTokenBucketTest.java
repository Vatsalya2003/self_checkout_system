package com.selfcheckout.self_checkout.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// The token bucket algorithm itself, with a clock the test moves by hand.
class LocalTokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aFullBucketAllowsExactlyBurstRequestsAtOnce() {
        LocalTokenBucket b = new LocalTokenBucket(10, 5, 0);
        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            if (b.tryAcquire(0).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void remainingCountsDownAndTheRefusalSaysWhenTheNextTokenComes() {
        LocalTokenBucket b = new LocalTokenBucket(10, 2, 0);
        assertThat(b.tryAcquire(0).remaining()).isEqualTo(1);
        assertThat(b.tryAcquire(0).remaining()).isEqualTo(0);
        BucketResult refused = b.tryAcquire(0);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMicros()).isEqualTo(100_000);   // 10 per second = one every 100 ms
        assertThat(refused.fullInMicros()).isEqualTo(200_000);
    }

    @Test
    void tokensComeBackAtTheConfiguredRate() {
        LocalTokenBucket b = new LocalTokenBucket(10, 1, 0);
        assertThat(b.tryAcquire(0).allowed()).isTrue();
        assertThat(b.tryAcquire(SECOND / 20).allowed()).isFalse();   // 50 ms: half a token
        assertThat(b.tryAcquire(SECOND / 10).allowed()).isTrue();    // 100 ms: a whole token
    }

    @Test
    void anIdleBucketNeverHoldsMoreThanTheBurst() {
        LocalTokenBucket b = new LocalTokenBucket(100, 3, 0);
        long later = 60 * SECOND;   // a minute idle would be 6,000 tokens without the cap
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (b.tryAcquire(later).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void overTenSecondsOfConstantDemandItAdmitsRateTimesTimePlusBurst() {
        LocalTokenBucket b = new LocalTokenBucket(50, 10, 0);
        int allowed = 0;
        // 1,000 attempts per second for 10 seconds, 20x the limit
        for (long t = 0; t <= 10 * SECOND; t += SECOND / 1_000) {
            if (b.tryAcquire(t).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(50 * 10 + 10);
    }
}
