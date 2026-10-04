package com.selfcheckout.self_checkout.analytics.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The thread half of the scan_log writer.
 *
 * It needs its own loop rather than FilterLoop because it is the one stage
 * that works on batches: wait for one row, then sweep up everything else
 * already waiting and write the lot in a single INSERT.
 *
 * That sweep is what makes the batching adaptive. Under light traffic the
 * sweep finds nothing and the batch is one row. Under load, or if this thread
 * was held up waiting for a pooled connection, the backlog is written in one
 * large statement -- so falling behind makes the next write more efficient
 * rather than less.
 */
public class ScanLogWriterLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScanLogWriterLoop.class);

    /** How long to wait for the first row of a batch before looping again. */
    private static final long IDLE_POLL_MILLIS = 50;

    private final String stage;
    private final Pipe<SequencedScanEvent> input;
    private final ScanLogWriterFilter filter;
    private final PipelineMetrics metrics;

    public ScanLogWriterLoop(String stage, Pipe<SequencedScanEvent> input,
                             ScanLogWriterFilter filter, PipelineMetrics metrics) {
        this.stage = stage;
        this.input = input;
        this.filter = filter;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        log.info("pipeline stage '{}' started (max batch {})", stage, filter.maxBatch());
        List<SequencedScanEvent> batch = new ArrayList<>(filter.maxBatch());
        try {
            while (true) {
                Optional<SequencedScanEvent> first = input.poll(IDLE_POLL_MILLIS);
                if (first.isEmpty()) {
                    if (input.isFinished()) {
                        break;                 // poisoned and drained
                    }
                    continue;                  // idle: nothing to write
                }
                batch.clear();
                batch.add(first.get());
                // Sweep up everything else already queued, up to the cap.
                input.drainTo(batch, filter.maxBatch() - 1);
                writeBatch(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("pipeline stage '{}' interrupted", stage);
        } finally {
            // Last chance: anything still queued at shutdown gets written.
            batch.clear();
            input.drainTo(batch, Integer.MAX_VALUE);
            if (!batch.isEmpty()) {
                log.info("pipeline stage '{}' flushing {} rows at shutdown", stage, batch.size());
                writeBatch(batch);
            }
            log.info("pipeline stage '{}' stopped", stage);
        }
    }

    private void writeBatch(List<SequencedScanEvent> batch) {
        metrics.recordIn(stage);                    // one "in" per batch
        try {
            int written = filter.flush(batch);
            metrics.recordOut(stage, written);      // "out" counts ROWS, so it can
        } catch (RuntimeException e) {              // be compared with ingest's total
            metrics.recordError(stage);
            log.warn("pipeline stage '{}' failed writing a batch of {} (seq {}..{})",
                    stage, batch.size(), batch.get(0).seq(), batch.get(batch.size() - 1).seq(), e);
        }
    }
}
