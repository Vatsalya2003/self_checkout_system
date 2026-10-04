package com.selfcheckout.self_checkout.transactions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRANSACTIONS layer. One basket that is still being scanned.
 *
 * Lives only in memory. Nothing about an unpaid basket reaches the database
 * until the customer actually pays, which is the whole point: start and scan
 * stop doing database work entirely.
 *
 * Thread safety: every field is guarded by this object's own monitor. One
 * basket is normally driven by one station, so the lock is uncontended -- but
 * it is what makes a scan racing a complete safe, which is exactly the bug the
 * Phase 0.5 database lock was fixing. The lock just moved to where the state
 * now lives.
 */
final class OpenBasket {

    /**
     * COMPLETING is a one-way door. Once a complete has claimed the basket,
     * a second complete gets 409 and a late scan gets 409 -- even though the
     * database work has not finished yet.
     */
    enum State { OPEN, COMPLETING }

    private final String id;
    private final String stationId;
    private final Instant startedAt;

    private State state = State.OPEN;
    private int itemCount = 0;
    private BigDecimal runningTotal = BigDecimal.ZERO;
    // insertion-ordered so the receipt lists lines in the order first scanned;
    // complete() sorts by SKU separately when it takes locks
    private final Map<String, Integer> lines = new LinkedHashMap<>();

    OpenBasket(String id, String stationId, Instant startedAt) {
        this.id = id;
        this.stationId = stationId;
        this.startedAt = startedAt;
    }

    String id() { return id; }
    String stationId() { return stationId; }
    Instant startedAt() { return startedAt; }

    /** Immutable view of the current state, taken under the lock. */
    synchronized Snapshot snapshot() {
        return new Snapshot(state, itemCount, runningTotal, Map.copyOf(lines));
    }

    /**
     * Add one unit. Returns the state after the scan, or null if the basket is
     * no longer OPEN (the caller turns that into 409).
     */
    synchronized Snapshot addUnit(String sku, BigDecimal unitPrice) {
        if (state != State.OPEN) {
            return null;
        }
        lines.merge(sku, 1, Integer::sum);
        itemCount += 1;
        runningTotal = runningTotal.add(unitPrice);
        return new Snapshot(state, itemCount, runningTotal, Map.copyOf(lines));
    }

    /**
     * Try to claim this basket for completion.
     *
     * Returns the basket contents on success. Returns null if it was not OPEN
     * (409 TRANSACTION_NOT_OPEN). Throws nothing -- the emptiness check is the
     * caller's, because an empty basket must stay OPEN and completable later,
     * which is how it behaved when this was a database transaction that rolled
     * back.
     */
    synchronized Snapshot claimForCompletion() {
        if (state != State.OPEN) {
            return null;
        }
        if (lines.isEmpty()) {
            return new Snapshot(state, 0, runningTotal, Map.of());   // caller -> 409 EMPTY, still OPEN
        }
        state = State.COMPLETING;
        return new Snapshot(state, itemCount, runningTotal, Map.copyOf(lines));
    }

    /** Put the basket back if the database work failed, so it can be retried. */
    synchronized void releaseClaim() {
        if (state == State.COMPLETING) {
            state = State.OPEN;
        }
    }

    /** What callers see; a plain immutable carrier. */
    record Snapshot(State state, int itemCount, BigDecimal runningTotal, Map<String, Integer> lines) {}
}
