package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// The poller is the hand-off from scan_log into the pipeline. These tests pin
// down the two rules the window bounds depend on: rows go downstream strictly
// in seq order, and a gap is never skipped.
class ScanLogPollerTest {

    private PipelineEndToEndTest.FakeScanLog scanLog;
    private BlockingQueuePipe<SequencedScanEvent> out;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        scanLog = new PipelineEndToEndTest.FakeScanLog();
        out = new BlockingQueuePipe<>("windowPipe", 100);
        metrics = new PipelineMetrics();
        metrics.registerStage("poller");
    }

    private static ScanLogRow row(long seq) {
        return new ScanLogRow(seq, "SKU-" + seq, Instant.now());
    }

    private List<Long> drained() {
        List<SequencedScanEvent> got = new ArrayList<>();
        out.drainTo(got, 1000);
        return got.stream().map(SequencedScanEvent::seq).toList();
    }

    @Test
    @DisplayName("hands rows downstream in seq order and remembers where it stopped")
    void readsInOrder() throws Exception {
        scanLog.commit(List.of(row(1), row(2), row(3)));
        ScanLogPoller p = new ScanLogPoller(scanLog, out, metrics, 0);
        assertEquals(3, p.pollOnce());
        assertEquals(List.of(1L, 2L, 3L), drained());
        assertEquals(0, p.pollOnce(), "nothing new: nothing handed over twice");
        scanLog.commit(List.of(row(4)));
        assertEquals(1, p.pollOnce());
        assertEquals(List.of(4L), drained());
        assertEquals(4, p.lastSeq());
    }

    @Test
    @DisplayName("stops at a gap instead of skipping it, and continues once it is filled")
    void neverSkipsAGap() throws Exception {
        scanLog.commit(List.of(row(1), row(2), row(4), row(5)));   // 3 is missing
        ScanLogPoller p = new ScanLogPoller(scanLog, out, metrics, 0);
        assertEquals(2, p.pollOnce());
        assertEquals(List.of(1L, 2L), drained());
        assertTrue(p.stalledAtGap());
        assertEquals(1, p.gapStalls());

        assertEquals(0, p.pollOnce(), "still waiting for seq 3");
        assertEquals(1, p.gapStalls(), "one stall is counted once, not once per poll");

        scanLog.rows.add(2, row(3));                               // the late row arrives
        assertEquals(3, p.pollOnce());
        assertEquals(List.of(3L, 4L, 5L), drained());
        assertFalse(p.stalledAtGap());
    }

    @Test
    @DisplayName("resumes after the seq it is told to start from (restart)")
    void resumesAfterStartSeq() throws Exception {
        scanLog.commit(List.of(row(1), row(2), row(3), row(4)));
        ScanLogPoller p = new ScanLogPoller(scanLog, out, metrics, 2);
        assertEquals(2, p.pollOnce());
        assertEquals(List.of(3L, 4L), drained());
    }
}
