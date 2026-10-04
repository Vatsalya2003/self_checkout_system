package com.selfcheckout.self_checkout.analytics.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * The fan-out at the end of ingest: number the scan, then hand it to the
 * window branch and (optionally) to the scan-log branch.
 *
 * This is a class rather than a lambda inside AnalyticsPipeline because the
 * two branches deliberately behave DIFFERENTLY, and that difference is worth
 * being able to test on its own:
 *
 *  - window branch: blocking put. This is the branch GET /analytics
 *    /popular-items is served from. If it falls behind, the correct response
 *    is to slow the pipeline down until it catches up. Losing a window would
 *    mean losing an answer.
 *
 *  - scan-log branch: non-blocking offer, drop and count on failure. This is
 *    an audit trail of raw rows. It is the slowest stage by far (one row per
 *    scan, ~8900 rows/sec under stress), and when it saturated, a blocking
 *    put here stalled this thread -- which stalled the window branch too,
 *    because they share it. A branch nobody reads live must not be able to
 *    starve the branch that is read live.
 *
 * Dropping is a real loss of audit rows, so it is counted and logged rather
 * than hidden. It only ever happens when the writer cannot keep up.
 */
public class IngestTee implements Consumer<ScanEvent> {

    private static final Logger log = LoggerFactory.getLogger(IngestTee.class);

    /** Not even a millisecond: the tee never waits on the audit branch. */
    private static final long NO_WAIT = 0;

    private final IngestFilter ingest;
    private final Pipe<SequencedScanEvent> windowPipe;
    private final Pipe<SequencedScanEvent> scanLogPipe;   // null when raw scans are off
    private final PipelineMetrics metrics;

    public IngestTee(IngestFilter ingest,
                     Pipe<SequencedScanEvent> windowPipe,
                     Pipe<SequencedScanEvent> scanLogPipe,
                     PipelineMetrics metrics) {
        this.ingest = ingest;
        this.windowPipe = windowPipe;
        this.scanLogPipe = scanLogPipe;
        this.metrics = metrics;
    }

    @Override
    public void accept(ScanEvent event) {
        SequencedScanEvent sequenced = ingest.assign(event);
        try {
            windowPipe.put(sequenced);
            if (scanLogPipe != null && !scanLogPipe.offer(sequenced, NO_WAIT)) {
                recordDrop();
            }
            metrics.recordOut("ingest");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingest interrupted", e);
        }
    }

    private void recordDrop() {
        metrics.recordRawScanDrop();
        long n = metrics.droppedRawScans();
        // One line per drop would itself become the bottleneck, so log the
        // first one and then only occasionally.
        if (n == 1 || n % 10_000 == 0) {
            log.warn("scan-log pipe full ({} deep) -- {} raw scan rows dropped so far; "
                     + "windows and the API are unaffected", scanLogPipe.depth(), n);
        }
    }

    /** Poison whatever is downstream of this tee, in order. */
    public void poisonDownstream() {
        windowPipe.poison();
        if (scanLogPipe != null) {
            scanLogPipe.poison();
        }
    }
}
