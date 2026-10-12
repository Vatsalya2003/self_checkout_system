package com.selfcheckout.self_checkout.scanlog;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogBatchWriter;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// The publisher is checkout-service's half of the scan hand-off. Its one job
// is to keep scan_log.seq gapless and in order, because analytics-service's
// window boundaries ("seq % 500 == 0") depend on it. These tests drive the
// write logic directly (writeOnce) instead of through the background thread,
// so they are exact rather than timing-dependent.
class ScanLogPublisherTest {

    /** Records every committed batch; can be told to fail the next N inserts. */
    static final class FakeWriter implements ScanLogBatchWriter {
        final List<List<ScanLogRow>> committed = new ArrayList<>();
        final List<Long> attemptedFirstSeqs = new ArrayList<>();
        int failNext = 0;
        long maxSeq = 0;

        @Override public void insertBatch(List<ScanLogRow> rows) {
            attemptedFirstSeqs.add(rows.get(0).seq());
            if (failNext > 0) { failNext--; throw new IllegalStateException("database unavailable"); }
            committed.add(List.copyOf(rows));
        }
        @Override public long maxSeq() { return maxSeq; }

        List<Long> allSeqs() {
            List<Long> out = new ArrayList<>();
            committed.forEach(b -> b.forEach(r -> out.add(r.seq())));
            return out;
        }
    }

    @Test
    @DisplayName("numbers scans 1, 2, 3, ... with no gaps across batches")
    void gaplessAcrossBatches() throws Exception {
        FakeWriter w = new FakeWriter();
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10);
        p.resumeFrom(w.maxSeq());
        for (int i = 0; i < 7; i++) p.recordScan("SKU-" + i);
        assertTrue(p.writeOnce(10));
        for (int i = 7; i < 12; i++) p.recordScan("SKU-" + i);
        assertTrue(p.writeOnce(10));
        assertFalse(p.writeOnce(10), "nothing queued: nothing written");

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), w.allSeqs());
        assertEquals("SKU-0", w.committed.get(0).get(0).sku());
        assertEquals("SKU-11", w.committed.get(1).get(4).sku());
        assertEquals(12, p.written());
        assertEquals(12, p.lastSeq());
    }

    @Test
    @DisplayName("a failed insert is retried with the SAME seqs, so no hole opens")
    void retriesWithSameSeqs() throws Exception {
        FakeWriter w = new FakeWriter();
        w.failNext = 2;
        ScanLogPublisher p = new ScanLogPublisher(w, 100, 10);
        p.resumeFrom(0);
        p.recordScan("SKU-A");
        p.recordScan("SKU-B");
        assertTrue(p.writeOnce(10));
        p.recordScan("SKU-C");
        assertTrue(p.writeOnce(10));

        assertEquals(List.of(1L, 1L, 1L, 3L), w.attemptedFirstSeqs, "two failures, then the same batch again");
        assertEquals(List.of(1L, 2L, 3L), w.allSeqs());
        assertEquals(2, p.writeErrors());
    }

    @Test
    @DisplayName("a full queue drops the scan event instead of blocking the request thread")
    void fullQueueDropsAndCounts() {
        FakeWriter w = new FakeWriter();
        ScanLogPublisher p = new ScanLogPublisher(w, 2, 5);     // capacity 2, wait 5 ms
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
    @DisplayName("a dropped event uses no seq number, so the sequence stays gapless")
    void dropsDoNotConsumeSeqs() throws Exception {
        FakeWriter w = new FakeWriter();
        ScanLogPublisher p = new ScanLogPublisher(w, 2, 1);
        p.resumeFrom(0);
        p.recordScan("SKU-1");
        p.recordScan("SKU-2");
        p.recordScan("SKU-DROPPED");
        assertTrue(p.writeOnce(10));
        p.recordScan("SKU-3");
        assertTrue(p.writeOnce(10));
        assertEquals(List.of(1L, 2L, 3L), w.allSeqs());
        assertEquals("SKU-3", w.committed.get(1).get(0).sku());
    }

    @Test
    @DisplayName("after a restart, numbering continues from MAX(seq) in scan_log")
    void resumesAfterRestart() throws Exception {
        FakeWriter w = new FakeWriter();
        w.maxSeq = 41_500;
        ScanLogPublisher p = new ScanLogPublisher(w, 10, 10);
        p.resumeFrom(w.maxSeq());
        p.recordScan("SKU-1");
        assertTrue(p.writeOnce(10));
        assertEquals(List.of(41_501L), w.allSeqs());
    }
}
