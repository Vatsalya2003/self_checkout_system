package com.selfcheckout.self_checkout.analytics.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * The thread half of a filter that works on batches.
 *
 * Shared by the scan-log writer and the sink, because they want exactly the
 * same behaviour: wait for one message, sweep up everything else already
 * queued, write the lot in one statement.
 *
 * That sweep is what makes the batching adaptive. Idle, it writes one row.
 * Behind, it writes a big batch and catches up -- so falling behind makes the
 * next write more efficient rather than less, which is the property that stops
 * a backlog running away.
 */
public class BatchingLoop<T> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BatchingLoop.class);
    private static final long IDLE_POLL_MILLIS = 50;

    private final String stage;
    private final Pipe<T> input;
    private final int maxBatch;
    private final ToIntFunction<List<T>> flush;   // returns rows written
    private final PipelineMetrics metrics;

    private long batches;

    public BatchingLoop(String stage, Pipe<T> input, int maxBatch,
                        ToIntFunction<List<T>> flush, PipelineMetrics metrics) {
        this.stage = stage;
        this.input = input;
        this.maxBatch = maxBatch;
        this.flush = flush;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        log.info("pipeline stage '{}' started (max batch {})", stage, maxBatch);
        List<T> batch = new ArrayList<>(Math.min(maxBatch, 1024));
        try {
            while (true) {
                Optional<T> first = input.poll(IDLE_POLL_MILLIS);
                if (first.isEmpty()) {
                    if (input.isFinished()) {
                        break;
                    }
                    continue;
                }
                batch.clear();
                batch.add(first.get());
                input.drainTo(batch, maxBatch - 1);
                write(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("pipeline stage '{}' interrupted", stage);
        } finally {
            batch.clear();
            input.drainTo(batch, Integer.MAX_VALUE);
            if (!batch.isEmpty()) {
                log.info("pipeline stage '{}' flushing {} at shutdown", stage, batch.size());
                write(batch);
            }
            log.info("pipeline stage '{}' stopped after {} batches", stage, batches);
        }
    }

    private void write(List<T> batch) {
        batches++;
        // Both counters are in ROWS, not batches. That is deliberate: the
        // acceptance check on a stress run is "in == out at every stage", and
        // counting batches in but rows out would make this stage the one
        // place that check could not be read straight off the metrics line.
        // The batch count is reported separately, by the sink cost line and
        // by the stage-stopped line above.
        metrics.recordIn(stage, batch.size());
        try {
            metrics.recordOut(stage, flush.applyAsInt(batch));
        } catch (RuntimeException e) {
            metrics.recordError(stage);
            log.warn("pipeline stage '{}' failed writing a batch of {}", stage, batch.size(), e);
        }
    }
}
