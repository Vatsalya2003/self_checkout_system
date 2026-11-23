package com.selfcheckout.self_checkout.ratelimit;

import java.util.function.LongSupplier;

// RATE-LIMIT library: stops calling Redis once it is clearly down, so a dead
// Redis costs a request nothing instead of a timeout.
//
//   CLOSED     normal: every check goes to Redis
//   OPEN       after N failures in a row: no Redis calls for a while (local fallback)
//   HALF_OPEN  the wait is over: ONE request probes Redis; success closes the
//              breaker, failure opens it again for another wait
//
// The CLOSED path is a single volatile read, so the breaker adds nothing
// measurable to a normal request.
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Told about every state change, so the limiter can log one clear line. */
    public interface Listener {
        void onStateChange(State from, State to, String reason);
    }

    private final int failureThreshold;
    private final long openForNanos;
    private final LongSupplier nanoClock;
    private final Listener listener;

    private volatile State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntilNanos;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, long openForNanos, LongSupplier nanoClock, Listener listener) {
        this.failureThreshold = failureThreshold;
        this.openForNanos = openForNanos;
        this.nanoClock = nanoClock;
        this.listener = listener;
    }

    /** May this request call Redis? */
    public boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        synchronized (this) {
            if (state == State.OPEN && nanoClock.getAsLong() >= openUntilNanos) {
                change(State.HALF_OPEN, "probing Redis");
            }
            if (state == State.HALF_OPEN && !probeInFlight) {
                probeInFlight = true;
                return true;
            }
            return state == State.CLOSED;
        }
    }

    public void recordSuccess() {
        if (state == State.CLOSED && consecutiveFailures == 0) {
            return;   // the common case: nothing to update, no lock taken
        }
        synchronized (this) {
            consecutiveFailures = 0;
            probeInFlight = false;
            if (state != State.CLOSED) {
                change(State.CLOSED, "Redis answered");
            }
        }
    }

    public synchronized void recordFailure(String reason) {
        consecutiveFailures++;
        probeInFlight = false;
        if (state == State.HALF_OPEN || (state == State.CLOSED && consecutiveFailures >= failureThreshold)) {
            openUntilNanos = nanoClock.getAsLong() + openForNanos;
            change(State.OPEN, reason);
        }
    }

    public State state() {
        return state;
    }

    private void change(State to, String reason) {
        State from = state;
        state = to;
        if (listener != null) {
            listener.onStateChange(from, to, reason);
        }
    }
}
