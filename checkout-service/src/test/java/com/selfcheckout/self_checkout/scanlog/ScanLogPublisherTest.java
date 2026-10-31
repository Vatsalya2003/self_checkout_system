package com.selfcheckout.self_checkout.scanlog;

import com.selfcheckout.self_checkout.remote.RecordedScan;
import com.selfcheckout.self_checkout.remote.ScanSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// The publisher is checkout-service's half of the scan hand-off (since Week 6
// a ScansRecorded event through RabbitMQ). Analytics numbers the scans, so
// the publisher's job is now: never block a scan, publish batches in order,
// and re-send an unconfirmed batch with the SAME batch id (analytics drops a
// batch id it already has, so a re-send can never duplicate). These tests
// drive the write logic directly (writeOnce) instead of through the
// background thread, so they are exact rather than timing-dependent.
class ScanLogPublisherTest {

    /**
     * Stands in for the broker: records every confirmed batch; can be told to
     * fail the next N publishes.
     */
    static final class FakeSink implements ScanSink {
        final List<String> confirmedIds = new ArrayList<>();
        final List<List<RecordedScan>> confirmed = new ArrayList<>();
        final List<String> attemptedIds = new ArrayList<>();
        int failNext = 0;

        @Override public void push(String batchId, List<RecordedScan> scans) {
            attemptedIds.add(batchId);
            if (failNext > 0) { failNext--; throw new IllegalStateException("broker unavailable"); }
            confirmedIds.add(batchId);
            confirmed.add(List.copyOf(scans));
        }

        List<String> allSkus() {
            List<String> out = new ArrayList<>();
            confirmed.forEach(b -> b.forEach(s -> out.add(s.sku())));
            return out;
        }
    }

    @Test
    @DisplayName("publishes scans in scan order, one batch per writeOnce, each with its own batch id")
    void inOrderBatches() throws Exception {
        FakeSink w = new FakeSink();
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10, 0);
        for (int i = 0; i < 7; i++) p.recordScan("SKU-" + i);
        assertTrue(p.writeOnce(10));
        for (int i = 7; i < 12; i++) p.recordScan("SKU-" + i);
        assertTrue(p.writeOnce(10));
        assertFalse(p.writeOnce(10), "nothing queued: nothing published");

        assertEquals(List.of("SKU-0", "SKU-1", "SKU-2", "SKU-3", "SKU-4", "SKU-5", "SKU-6",
                             "SKU-7", "SKU-8", "SKU-9", "SKU-10", "SKU-11"), w.allSkus());
        assertEquals(2, w.confirmedIds.size());
        assertNotEquals(w.confirmedIds.get(0), w.confirmedIds.get(1), "two batches, two ids");
        assertEquals(12, p.written());
    }

    @Test
    @DisplayName("an unconfirmed batch is re-sent with the SAME batch id, so analytics can drop a second copy")
    void retriesWithSameBatchId() throws Exception {
        FakeSink w = new FakeSink();
        w.failNext = 2;
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10, 0);
        p.recordScan("SKU-A");
        p.recordScan("SKU-B");
        assertTrue(p.writeOnce(10));
        p.recordScan("SKU-C");
        assertTrue(p.writeOnce(10));

        assertEquals(4, w.attemptedIds.size(), "two failures, then the same batch again, then the next batch");
        assertEquals(w.attemptedIds.get(0), w.attemptedIds.get(1));
        assertEquals(w.attemptedIds.get(0), w.attemptedIds.get(2));
        assertNotEquals(w.attemptedIds.get(0), w.attemptedIds.get(3));
        assertEquals(List.of("SKU-A", "SKU-B", "SKU-C"), w.allSkus(), "each scan confirmed exactly once");
        assertEquals(2, p.writeErrors());
    }

    @Test
    @DisplayName("a full queue drops the scan event instead of blocking the request thread")
    void fullQueueDropsAndCounts() {
        FakeSink w = new FakeSink();
        ScanLogPublisher p = new ScanLogPublisher(w, 2, 5, 0);     // capacity 2, wait 5 ms
        long t0 = System.nanoTime();
        p.recordScan("SKU-1");
        p.recordScan("SKU-2");
        p.recordScan("SKU-3");                                   // no room
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(2, p.accepted());
        assertEquals(1, p.dropped());
        assertTrue(elapsedMs < 1_000, "the caller waited " + elapsedMs + " ms; must give up quickly");
    }

    @Test
    @DisplayName("a dropped event is never published, and the next scans still go out in order")
    void dropsAreNotPublished() throws Exception {
        FakeSink w = new FakeSink();
        ScanLogPublisher p = new ScanLogPublisher(w, 2, 1, 0);
        p.recordScan("SKU-1");
        p.recordScan("SKU-2");
        p.recordScan("SKU-DROPPED");
        assertTrue(p.writeOnce(10));
        p.recordScan("SKU-3");
        assertTrue(p.writeOnce(10));
        assertEquals(List.of("SKU-1", "SKU-2", "SKU-3"), w.allSkus());
    }

    @Test
    @DisplayName("collects for up to the linger time, so a burst becomes one message")
    void lingerCollectsABatch() throws Exception {
        FakeSink w = new FakeSink();
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10, 200);
        p.recordScan("SKU-1");
        Thread late = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
            p.recordScan("SKU-2");
            p.recordScan("SKU-3");
        });
        late.start();
        assertTrue(p.writeOnce(10));
        late.join();
        assertEquals(1, w.confirmed.size(), "the late scans joined the same batch");
        assertEquals(List.of("SKU-1", "SKU-2", "SKU-3"), w.allSkus());
    }

    @Test
    @DisplayName("broker down: the customer is not held up, scans wait in the queue and go out once it is back")
    void brokerDownBuffers() throws Exception {
        FakeSink w = new FakeSink();
        w.failNext = 3;
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10, 0);
        p.recordScan("SKU-1");                 // accepted immediately
        p.recordScan("SKU-2");
        assertEquals(2, p.accepted());
        assertTrue(p.writeOnce(10));           // retries through the outage
        assertEquals(List.of("SKU-1", "SKU-2"), w.allSkus());
        assertEquals(3, p.writeErrors());
    }
}
