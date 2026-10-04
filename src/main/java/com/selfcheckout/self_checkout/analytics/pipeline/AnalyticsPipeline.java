package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.repository.CatalogNameLookup;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogBatchWriter;
import com.selfcheckout.self_checkout.persistence.repository.SnapshotWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the pipeline, owns its threads, and is the only way in.
 *
 * Shape (see PIPELINE.md):
 *
 *   request thread --offer--> [ingest] --+--> [window] --> [rank] --> [sink]
 *                                        |
 *                                        +--> [scan-log writer]
 *
 * One thread per filter, bounded queue between each pair.
 */
@Component
public class AnalyticsPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPipeline.class);

    /** The assignment requires the top 10 to be written to the database. */
    private static final int TOP_N = 10;

    // ---- sizing -------------------------------------------------------
    // The three event pipes carry one message per scan. At the ~3600 scans/sec
    // measured in Week 2, 65536 slots is roughly 18 seconds of buffer, and
    // three of them cost about 20 MB at worst. The two window pipes carry one
    // message per 500 scans -- about 7 per second -- so 256 is already absurd
    // headroom.
    private static final int EVENT_PIPE_CAPACITY = 65_536;
    private static final int WINDOW_PIPE_CAPACITY = 256;

    // Biggest single INSERT. 1000 rows x 3 columns = 3000 bind parameters,
    // well under Postgres' 65535 limit.
    private static final int MAX_BATCH = 1_000;

    /**
     * How long the REQUEST thread will wait to hand over an event.
     *
     * Deliberately tiny. Between filters a full pipe means "slow down", which
     * is correct. At this boundary it would mean "a stalled analytics stage
     * can stall checkout", which is not: in Week 2 analytics could never
     * affect the checkout path, and that must stay true. So here we give up
     * almost immediately, count the drop, and let the scan finish.
     */
    private static final long INGRESS_OFFER_MILLIS = 10;

    private static final long METRICS_INTERVAL_MILLIS = 10_000;

    private final SelfCheckoutProperties properties;
    private final ScanLogBatchWriter scanLogBatchWriter;
    private final SnapshotWriter snapshotWriter;
    private final CatalogNameLookup catalogNames;
    private final ObjectMapper objectMapper;

    private final PipelineMetrics metrics = new PipelineMetrics();

    private Pipe<ScanEvent> ingestPipe;
    private Pipe<SequencedScanEvent> windowPipe;
    private Pipe<SequencedScanEvent> scanLogPipe;
    private Pipe<WindowCounts> rankPipe;
    private Pipe<RankedWindow> sinkPipe;
    private List<Pipe<?>> allPipes = List.of();

    private final List<Thread> threads = new ArrayList<>();
    private volatile Thread metricsThread;

    public AnalyticsPipeline(SelfCheckoutProperties properties,
                             ScanLogBatchWriter scanLogBatchWriter,
                             SnapshotWriter snapshotWriter,
                             CatalogNameLookup catalogNames,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.scanLogBatchWriter = scanLogBatchWriter;
        this.snapshotWriter = snapshotWriter;
        this.catalogNames = catalogNames;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        ingestPipe  = new BlockingQueuePipe<>("ingestPipe",  EVENT_PIPE_CAPACITY);
        windowPipe  = new BlockingQueuePipe<>("windowPipe",  EVENT_PIPE_CAPACITY);
        scanLogPipe = new BlockingQueuePipe<>("scanLogPipe", EVENT_PIPE_CAPACITY);
        rankPipe    = new BlockingQueuePipe<>("rankPipe",    WINDOW_PIPE_CAPACITY);
        sinkPipe    = new BlockingQueuePipe<>("sinkPipe",    WINDOW_PIPE_CAPACITY);
        allPipes = List.of(ingestPipe, windowPipe, scanLogPipe, rankPipe, sinkPipe);

        for (String s : List.of("ingest", "window", "rank", "sink", "scanlog")) {
            metrics.registerStage(s);
        }

        IngestFilter ingest = new IngestFilter();
        WindowFilter window = new WindowFilter(properties.popularWindowSize(),
                                               properties.popularSlideInterval());
        RankFilter rank = new RankFilter(TOP_N, catalogNames);
        SinkFilter sink = new SinkFilter(snapshotWriter, objectMapper);
        ScanLogWriterFilter writer = new ScanLogWriterFilter(scanLogBatchWriter, MAX_BATCH);

        // Between filters we use blocking put: if a downstream stage is slow,
        // the stage above it should wait rather than throw work away. That
        // backpressure stays strictly inside the pipeline.
        startThread("pipeline-ingest", new FilterLoop<>("ingest", ingestPipe,
                event -> {
                    SequencedScanEvent sequenced = ingest.assign(event);
                    try {
                        windowPipe.put(sequenced);     // tee, arm 1
                        scanLogPipe.put(sequenced);    // tee, arm 2
                        metrics.recordOut("ingest");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("ingest interrupted", e);
                    }
                },
                () -> { windowPipe.poison(); scanLogPipe.poison(); },
                metrics));

        startThread("pipeline-window", new FilterLoop<>("window", windowPipe,
                event -> window.accept(event).ifPresent(counts -> {
                    try {
                        rankPipe.put(counts);
                        metrics.recordOut("window");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("window interrupted", e);
                    }
                }),
                () -> rankPipe.poison(), metrics));

        startThread("pipeline-rank", new FilterLoop<>("rank", rankPipe,
                counts -> {
                    try {
                        sinkPipe.put(rank.rank(counts));
                        metrics.recordOut("rank");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("rank interrupted", e);
                    }
                },
                () -> sinkPipe.poison(), metrics));

        startThread("pipeline-sink", new FilterLoop<>("sink", sinkPipe,
                ranked -> { sink.accept(ranked); metrics.recordOut("sink"); },
                () -> { }, metrics));

        startThread("pipeline-scanlog", new ScanLogWriterLoop("scanlog", scanLogPipe, writer, metrics));

        startMetricsReporter();
        log.info("analytics pipeline started: 5 filters, 5 bounded queue pipes");
    }

    private void startThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);      // a wedged stage must never keep the JVM alive
        t.start();
        threads.add(t);
    }

    private void startMetricsReporter() {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(METRICS_INTERVAL_MILLIS);
                    log.info(metrics.summary(allPipes));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-metrics");
        t.setDaemon(true);
        t.start();
        metricsThread = t;
    }

    /**
     * The only entry point. Called on the REQUEST thread from an afterCommit
     * callback, so by the time we get here the scan is durable.
     *
     * @return true if the event entered the pipeline
     */
    public boolean submit(String sku, Instant scannedAt) {
        try {
            boolean accepted = ingestPipe.offer(new ScanEvent(sku, scannedAt), INGRESS_OFFER_MILLIS);
            if (!accepted) {
                metrics.recordDrop();
                log.warn("ingest pipe full ({} deep) -- analytics event for {} dropped; "
                         + "checkout is unaffected", ingestPipe.depth(), sku);
            }
            return accepted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // never swallow the flag
            metrics.recordDrop();
            return false;
        }
    }

    /**
     * Stop in order: poison the front of the pipe and let each stage drain
     * and poison the next. The scan_log writer flushes whatever is left.
     *
     * Runs before Spring closes the DataSource, because this bean depends on
     * the writers, which depend on the DataSource -- destruction is in reverse
     * dependency order.
     */
    @PreDestroy
    public void stop() {
        log.info("analytics pipeline shutting down: {}", metrics.summary(allPipes));
        ingestPipe.poison();
        for (Thread t : threads) {
            try {
                t.join(10_000);      // generous: the last batch still has to be written
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (metricsThread != null) {
            metricsThread.interrupt();
        }
        log.info("analytics pipeline stopped: {}", metrics.summary(allPipes));
    }

    /** Visible for tests and for the shutdown/report logging. */
    public PipelineMetrics metrics() { return metrics; }

    public List<Pipe<?>> pipes() { return allPipes; }
}
