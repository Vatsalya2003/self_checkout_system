package com.selfcheckout.self_checkout.analytics.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tee is where the two branches stop being symmetric, so these tests are
 * about the ASYMMETRY, not about fan-out.
 *
 * The bug being locked out: the scan-log branch used a blocking put, so when
 * the raw-scan writer saturated it stalled the ingest thread -- and the
 * window branch, which shares that thread, went idle behind an audit trail
 * nobody reads live.
 */
class IngestTeeTest {

    private static final int CAPACITY = 8;

    private PipelineMetrics metrics() {
        PipelineMetrics m = new PipelineMetrics();
        m.registerStage("ingest");
        return m;
    }

    private static ScanEvent ev(int i) {
        return new ScanEvent("SKU-" + i, Instant.EPOCH);
    }

    private static List<SequencedScanEvent> drain(Pipe<SequencedScanEvent> pipe) {
        List<SequencedScanEvent> out = new ArrayList<>();
        pipe.drainTo(out, Integer.MAX_VALUE);
        return out;
    }

    @Test
    @DisplayName("with room on both sides, every scan reaches both branches exactly once")
    void teesToBothBranches() {
        PipelineMetrics metrics = metrics();
        Pipe<SequencedScanEvent> window = new BlockingQueuePipe<>("windowPipe", CAPACITY);
        Pipe<SequencedScanEvent> scanLog = new BlockingQueuePipe<>("scanLogPipe", CAPACITY);
        IngestTee tee = new IngestTee(new IngestFilter(), window, scanLog, metrics);

        for (int i = 1; i <= CAPACITY; i++) {
            tee.accept(ev(i));
        }

        assertEquals(CAPACITY, drain(window).size());
        assertEquals(CAPACITY, drain(scanLog).size());
        assertEquals(0, metrics.droppedRawScans(), "nothing should be dropped when there is room");
        assertEquals(CAPACITY, metrics.out("ingest"));
    }

    @Test
    @DisplayName("a FULL scan-log pipe is dropped from, never waited on, and the window branch is untouched")
    void fullScanLogBranchDropsInsteadOfBlocking() {
        PipelineMetrics metrics = metrics();
        // The window side is given room for everything; the scan-log side is
        // filled to the brim first, so every offer to it must fail.
        Pipe<SequencedScanEvent> window = new BlockingQueuePipe<>("windowPipe", 100);
        Pipe<SequencedScanEvent> scanLog = new BlockingQueuePipe<>("scanLogPipe", CAPACITY);
        IngestTee tee = new IngestTee(new IngestFilter(), window, scanLog, metrics);

        for (int i = 1; i <= CAPACITY; i++) {
            tee.accept(ev(i));                     // fills scanLog exactly
        }
        assertEquals(CAPACITY, scanLog.depth(), "precondition: the scan-log pipe is full");

        long started = System.nanoTime();
        for (int i = 1; i <= 50; i++) {
            tee.accept(ev(1000 + i));              // must not block
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1000,
                "50 scans past a full audit pipe took " + elapsedMillis + "ms -- the tee blocked");
        assertEquals(50, metrics.droppedRawScans(), "every refused row must be counted, not hidden");
        assertEquals(CAPACITY + 50, drain(window).size(),
                "the window branch must receive every scan even while the audit branch is full");
        assertEquals(CAPACITY + 50, metrics.out("ingest"));
    }

    @Test
    @DisplayName("with raw scans off there is no second branch at all, and no drops are counted")
    void worksWithoutAScanLogBranch() {
        PipelineMetrics metrics = metrics();
        Pipe<SequencedScanEvent> window = new BlockingQueuePipe<>("windowPipe", CAPACITY);
        IngestTee tee = new IngestTee(new IngestFilter(), window, null, metrics);

        for (int i = 1; i <= CAPACITY; i++) {
            tee.accept(ev(i));
        }

        assertEquals(CAPACITY, drain(window).size());
        assertEquals(0, metrics.droppedRawScans(),
                "a branch that does not exist cannot drop anything");
        tee.poisonDownstream();                    // must not NPE on the absent branch
        assertTrue(window.isFinished());
    }

    @Test
    @DisplayName("sequence numbers stay gapless even when the audit branch is dropping")
    void sequenceIsUnaffectedByDrops() {
        PipelineMetrics metrics = metrics();
        Pipe<SequencedScanEvent> window = new BlockingQueuePipe<>("windowPipe", 100);
        Pipe<SequencedScanEvent> scanLog = new BlockingQueuePipe<>("scanLogPipe", 1);
        IngestTee tee = new IngestTee(new IngestFilter(), window, scanLog, metrics);

        for (int i = 1; i <= 20; i++) {
            tee.accept(ev(i));
        }

        List<SequencedScanEvent> got = drain(window);
        for (int i = 0; i < got.size(); i++) {
            assertEquals(i + 1, got.get(i).seq(), "the window branch must still see 1..n with no gaps");
        }
        assertEquals(19, metrics.droppedRawScans());
    }

    @Test
    @DisplayName("poisonDownstream closes both branches when both exist")
    void poisonsBothBranches() {
        Pipe<SequencedScanEvent> window = new BlockingQueuePipe<>("windowPipe", CAPACITY);
        Pipe<SequencedScanEvent> scanLog = new BlockingQueuePipe<>("scanLogPipe", CAPACITY);
        IngestTee tee = new IngestTee(new IngestFilter(), window, scanLog, metrics());

        tee.poisonDownstream();

        assertTrue(window.isFinished());
        assertTrue(scanLog.isFinished());
    }
}
