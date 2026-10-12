package com.selfcheckout.self_checkout.analytics.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

// The window filter is a plain class with no threads and no Spring, which is
// exactly why it can be tested like this.
class WindowFilterTest {

    private static SequencedScanEvent ev(long seq, String sku) {
        return new SequencedScanEvent(seq, sku, Instant.EPOCH);
    }

    @Test
    @DisplayName("emits exactly on every slide boundary and nowhere else")
    void emitsOnlyOnBoundaries() {
        WindowFilter w = new WindowFilter(1000, 500);
        List<Long> emittedAt = new ArrayList<>();
        for (long seq = 1; seq <= 2600; seq++) {
            w.accept(ev(seq, "SKU-" + (seq % 7))).ifPresent(c -> emittedAt.add(c.windowEnd()));
        }
        assertEquals(List.of(500L, 1000L, 1500L, 2000L, 2500L), emittedAt);
    }

    @Test
    @DisplayName("window is half-open (start, end] and clamps at zero early on")
    void halfOpenAndClamped() {
        WindowFilter w = new WindowFilter(1000, 500);
        WindowCounts first = null, second = null, third = null;
        for (long seq = 1; seq <= 1500; seq++) {
            Optional<WindowCounts> c = w.accept(ev(seq, "SKU-A"));
            if (c.isPresent()) {
                if (first == null) first = c.get();
                else if (second == null) second = c.get();
                else third = c.get();
            }
        }
        assertEquals(0, first.windowStart());    assertEquals(500, first.windowEnd());
        assertEquals(0, second.windowStart());   assertEquals(1000, second.windowEnd());
        assertEquals(500, third.windowStart());  assertEquals(1500, third.windowEnd());
    }

    @Test
    @DisplayName("evicts at windowSize: only the last 1000 scans are counted")
    void evictsOldest() {
        WindowFilter w = new WindowFilter(1000, 500);
        // 1000 of OLD, then 1000 of NEW. At seq 2000 the window is (1000,2000]
        // so OLD must have fallen out completely.
        for (long seq = 1; seq <= 1000; seq++) w.accept(ev(seq, "OLD"));
        WindowCounts atTwoThousand = null;
        for (long seq = 1001; seq <= 2000; seq++) {
            Optional<WindowCounts> c = w.accept(ev(seq, "NEW"));
            if (c.isPresent() && c.get().windowEnd() == 2000) atTwoThousand = c.get();
        }
        assertEquals(1000, w.windowOccupancy());
        assertNull(atTwoThousand.counts().get("OLD"), "OLD should have been evicted");
        assertEquals(1000L, atTwoThousand.counts().get("NEW"));
    }

    @Test
    @DisplayName("counts are correct across a boundary, not just at the ends")
    void countsAcrossBoundary() {
        WindowFilter w = new WindowFilter(1000, 500);
        // 750 x A then 750 x B. Window (500,1500] holds 250 A and 750 B.
        WindowCounts last = null;
        for (long seq = 1; seq <= 1500; seq++) {
            Optional<WindowCounts> c = w.accept(ev(seq, seq <= 750 ? "A" : "B"));
            if (c.isPresent()) last = c.get();
        }
        assertEquals(1500, last.windowEnd());
        assertEquals(250L, last.counts().get("A"));
        assertEquals(750L, last.counts().get("B"));
    }

    @Test
    @DisplayName("a SKU that drops to zero leaves the count map entirely")
    void zeroCountsAreRemoved() {
        WindowFilter w = new WindowFilter(10, 5);
        for (long seq = 1; seq <= 10; seq++) w.accept(ev(seq, "GONE"));
        for (long seq = 11; seq <= 20; seq++) w.accept(ev(seq, "HERE"));
        assertEquals(1, w.distinctSkus(), "GONE should not linger with a count of 0");
    }

    @Test
    @DisplayName("emitted counts are a snapshot, not a live view of the filter")
    void emittedCountsAreImmutableSnapshot() {
        WindowFilter w = new WindowFilter(1000, 500);
        WindowCounts snap = null;
        for (long seq = 1; seq <= 500; seq++) {
            Optional<WindowCounts> c = w.accept(ev(seq, "A"));
            if (c.isPresent()) snap = c.get();
        }
        final WindowCounts captured = snap;
        assertEquals(500L, captured.counts().get("A"));
        for (long seq = 501; seq <= 600; seq++) w.accept(ev(seq, "A"));
        assertEquals(500L, captured.counts().get("A"), "the snapshot must not have moved");
        assertThrows(UnsupportedOperationException.class, () -> captured.counts().put("X", 1L));
    }
}
