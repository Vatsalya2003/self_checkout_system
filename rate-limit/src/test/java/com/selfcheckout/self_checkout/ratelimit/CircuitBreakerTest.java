package com.selfcheckout.self_checkout.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong();
    private final List<String> changes = new ArrayList<>();
    private final CircuitBreaker breaker = new CircuitBreaker(3, 1_000_000_000L, now::get,
            (from, to, reason) -> changes.add(from + "->" + to));

    @Test
    void opensAfterThreeFailuresInARowAndThenStopsCallingRedis() {
        breaker.recordFailure("x");
        breaker.recordFailure("x");
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure("x");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.recordFailure("x");
        breaker.recordFailure("x");
        breaker.recordSuccess();
        breaker.recordFailure("x");
        breaker.recordFailure("x");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void afterTheWaitExactlyOneRequestProbesAndASuccessClosesIt() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure("x");
        }
        now.set(1_000_000_000L);
        assertThat(breaker.allowRequest()).isTrue();    // the probe
        assertThat(breaker.allowRequest()).isFalse();   // everyone else keeps using the fallback
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(changes).containsExactly("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED");
    }

    @Test
    void aFailedProbeOpensItForAnotherWait() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure("x");
        }
        now.set(1_000_000_000L);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure("still down");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        now.set(1_500_000_000L);
        assertThat(breaker.allowRequest()).isFalse();
    }
}
