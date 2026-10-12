package com.selfcheckout.self_checkout.analytics.pipeline;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FILTER 2 of 4 -- the hopping window. (ANALYTICS-SERVICE)
 *
 * Keeps the last windowSize scans in a ring buffer plus a running count per
 * SKU, and every slideInterval scans emits a snapshot of those counts.
 *
 * Why in memory instead of the Week 2 SQL aggregate: the old version re-read
 * 1000 rows out of Postgres every 500 scans. Here each event is one array
 * write and two map updates, and the database is not involved at all.
 *
 * Single-threaded by contract. Nothing here is synchronized because exactly
 * one thread ever calls accept(); that is what makes a filter cheap.
 */
public class WindowFilter {

    private final int windowSize;
    private final int slideInterval;

    private final String[] ring;          // the last windowSize SKUs, oldest overwritten
    private int ringCount = 0;            // how many slots are actually filled
    private int ringNext = 0;             // where the next SKU goes

    private final Map<String, Long> counts = new HashMap<>();

    public WindowFilter(int windowSize, int slideInterval) {
        this.windowSize = windowSize;
        this.slideInterval = slideInterval;
        this.ring = new String[windowSize];
    }

    /**
     * Take one scan. Returns a window when this event lands on a boundary,
     * otherwise empty.
     *
     * The boundary test is a plain "seq % slideInterval == 0" and that is now
     * SAFE, unlike in Week 2. Back then seq came from a BIGSERIAL, which burns
     * numbers on rolled-back inserts, so an exact multiple could never appear
     * and a window would be skipped forever -- hence the old AtomicLong
     * "which boundary have I passed" dance. The ingest filter numbers only
     * scans that already committed, one at a time, so the sequence has no
     * gaps and every boundary is hit exactly once.
     *
     * Week 4: the numbering now happens in checkout-service's scan-log writer
     * (one thread, batches committed strictly in order), and the poller hands
     * rows over only if each one is exactly the previous seq + 1. So the same
     * guarantee holds across the process boundary.
     */
    public Optional<WindowCounts> accept(SequencedScanEvent event) {
        // Evict the scan falling out of the window, if the ring is full.
        if (ringCount == windowSize) {
            String evicted = ring[ringNext];
            counts.compute(evicted, (sku, n) -> (n == null || n <= 1) ? null : n - 1);
        } else {
            ringCount++;
        }

        ring[ringNext] = event.sku();
        ringNext = (ringNext + 1) % windowSize;
        counts.merge(event.sku(), 1L, Long::sum);

        if (event.seq() % slideInterval != 0) {
            return Optional.empty();
        }

        // Half-open (start, end]: consecutive windows neither double-count nor
        // skip the scan sitting exactly on the boundary. Early in a run there
        // are fewer than windowSize scans, so clamp the start at 0.
        long windowEnd = event.seq();
        long windowStart = Math.max(0, windowEnd - windowSize);

        // Copy, because this filter keeps mutating its own map afterwards.
        return Optional.of(new WindowCounts(windowStart, windowEnd,
                Map.copyOf(new LinkedHashMap<>(counts))));
    }

    /** Visible for tests: the live count map size. */
    int distinctSkus() { return counts.size(); }

    /** Visible for tests: how many scans are currently inside the window. */
    int windowOccupancy() { return ringCount; }
}
