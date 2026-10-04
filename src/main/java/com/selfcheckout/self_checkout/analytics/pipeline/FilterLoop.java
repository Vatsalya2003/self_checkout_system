package com.selfcheckout.self_checkout.analytics.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The thread half of a filter.
 *
 * Filters themselves are plain classes with no threading in them, which is
 * what makes them unit-testable without Spring or a database. This class is
 * the only place that knows about threads, and every stage except the
 * batching writer reuses it.
 *
 * Two rules it enforces:
 *  - one bad message never kills a stage: the exception is logged, counted,
 *    and the loop carries on with the next message
 *  - shutdown drains: the loop only exits once the upstream pipe is poisoned
 *    AND empty, then poisons its own downstream so the whole chain winds down
 *    in order
 */
public class FilterLoop<T> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FilterLoop.class);

    /** Short poll so shutdown is responsive without a busy loop. */
    private static final long POLL_MILLIS = 100;

    private final String stage;
    private final Pipe<T> input;
    private final Consumer<T> handler;
    private final Runnable onFinished;      // poison whatever is downstream
    private final PipelineMetrics metrics;

    public FilterLoop(String stage, Pipe<T> input, Consumer<T> handler,
                      Runnable onFinished, PipelineMetrics metrics) {
        this.stage = stage;
        this.input = input;
        this.handler = handler;
        this.onFinished = onFinished;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        log.info("pipeline stage '{}' started", stage);
        try {
            while (true) {
                Optional<T> message = input.poll(POLL_MILLIS);
                if (message.isPresent()) {
                    metrics.recordIn(stage);
                    try {
                        handler.accept(message.get());
                    } catch (RuntimeException e) {
                        // Analytics must never take checkout down, and one
                        // poison message must never silence a whole stage.
                        metrics.recordError(stage);
                        log.warn("pipeline stage '{}' failed on {}", stage, message.get(), e);
                    }
                } else if (input.isFinished()) {
                    break;                        // poisoned and drained
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // restore the flag, then stop
            log.warn("pipeline stage '{}' interrupted", stage);
        } finally {
            onFinished.run();
            log.info("pipeline stage '{}' stopped", stage);
        }
    }
}
