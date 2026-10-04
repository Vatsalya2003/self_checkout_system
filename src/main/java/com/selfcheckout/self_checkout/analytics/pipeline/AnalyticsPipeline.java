package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
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
 *   request thread --offer--> [ingest] --put--> [window] --> [rank] --> [sink]
 *                                        |
 *                                        +--offer--> [scan-log writer]   (optional)
 *
 * One thread per filter, bounded queue between each pair.
 *
 * Two things about the tee are deliberate:
 *  - the window branch uses a blocking put, the scan-log branch a
 *    non-blocking offer, so the optional audit trail can never slow down or
 *    starve the branch the API serves from
 *  - the scan-log branch is only built when selfcheckout.analytics
 *    .persist-raw-scans is true; with it off the stage does not exist at all
 */
@Component
public class AnalyticsPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPipeline.class);

    /** The assignment requires the top 10 to be written to the database. */
    private static final int TOP_N = 10;

    // ---- sizing -------------------------------------------------------
    // The event pipes carry one message per scan. At the ~8900 scans/sec this
    // server now reaches under stress, 65536 slots is about 7 seconds of
    // buffer, and they cost about 20 MB at worst.
    private static final int EVENT_PIPE_CAPACITY = 65_536;

    // The two window pipes carry one message per slideInterval scans -- about
    // 18 per second at stress rates. They were 256, which filled, because the
    // sink wrote one row per commit and could not keep up; the backlog pushed
    // all the way up to the window filter. The real fix for that is the
    // batched sink below, NOT a bigger queue. This headroom is only so a
    // transient stall (a long pool wait, a GC pause) is absorbed instead of
    // propagated: 4096 slots is over three minutes of windows.
    private static final int WINDOW_PIPE_CAPACITY = 4_096;

    // Biggest single INSERT. 1000 rows x 3 columns = 3000 bind parameters,
    // well under Postgres' 65535 limit.
    private static final int MAX_BATCH = 1_000;

    // Snapshot rows are much fatter (each carries the top-10 JSON), so the
    // window batch is smaller: 512 rows x 4 columns = 2048 bind parameters.
    private static final int MAX_SNAPSHOT_BATCH = 512;

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
    private final CatalogCache catalogNames;
    private final ObjectMapper objectMapper;

    private final PipelineMetrics metrics = new PipelineMetrics();

    private Pipe<ScanEvent> ingestPipe;
    private Pipe<SequencedScanEvent> windowPipe;
    private Pipe<SequencedScanEvent> scanLogPipe;
    private Pipe<WindowCounts> rankPipe;
    private Pipe<RankedWindow> sinkPipe;
    private List<Pipe<?>> allPipes = List.of();
    private boolean persistRawScans;

    private final List<Thread> threads = new ArrayList<>();
    private volatile Thread metricsThread;

    public AnalyticsPipeline(SelfCheckoutProperties properties,
                             ScanLogBatchWriter scanLogBatchWriter,
                             SnapshotWriter snapshotWriter,
                             CatalogCache catalogNames,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.scanLogBatchWriter = scanLogBatchWriter;
        this.snapshotWriter = snapshotWriter;
        this.catalogNames = catalogNames;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        persistRawScans = properties.analytics().persistRawScans();

        ingestPipe  = new BlockingQueuePipe<>("ingestPipe",  EVENT_PIPE_CAPACITY);
        windowPipe  = new BlockingQueuePipe<>("windowPipe",  EVENT_PIPE_CAPACITY);
        rankPipe    = new BlockingQueuePipe<>("rankPipe",    WINDOW_PIPE_CAPACITY);
        sinkPipe    = new BlockingQueuePipe<>("sinkPipe",    WINDOW_PIPE_CAPACITY);

        List<String> stages = new ArrayList<>(List.of("ingest", "window", "rank", "sink"));
        List<Pipe<?>> pipes = new ArrayList<>(List.of(ingestPipe, windowPipe, rankPipe, sinkPipe));

        // The scan-log branch only exists when raw scans are being persisted.
        // With the flag off there is no pipe, no thread and no writer -- the
        // pipeline is ingest -> window -> rank -> sink.
        if (persistRawScans) {
            scanLogPipe = new BlockingQueuePipe<>("scanLogPipe", EVENT_PIPE_CAPACITY);
            stages.add("scanlog");
            pipes.add(scanLogPipe);
        }
        allPipes = List.copyOf(pipes);
        for (String s : stages) {
            metrics.registerStage(s);
        }

        IngestFilter ingest = new IngestFilter();
        WindowFilter window = new WindowFilter(properties.popularWindowSize(),
                                               properties.popularSlideInterval());
        RankFilter rank = new RankFilter(TOP_N, catalogNames);
        SinkFilter sink = new SinkFilter(snapshotWriter, objectMapper);

        IngestTee tee = new IngestTee(ingest, windowPipe, scanLogPipe, metrics);
        startThread("pipeline-ingest",
                new FilterLoop<>("ingest", ingestPipe, tee, tee::poisonDownstream, metrics));

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

        startThread("pipeline-sink",
                new BatchingLoop<>("sink", sinkPipe, MAX_SNAPSHOT_BATCH, sink::flush, metrics));

        if (persistRawScans) {
            ScanLogWriterFilter writer = new ScanLogWriterFilter(scanLogBatchWriter);
            startThread("pipeline-scanlog",
                    new BatchingLoop<>("scanlog", scanLogPipe, MAX_BATCH, writer::flush, metrics));
        }

        startMetricsReporter();
        log.info("analytics pipeline started: {} filters, {} bounded queue pipes (raw scan persistence {})",
                stages.size(), allPipes.size(), persistRawScans ? "ON" : "OFF");
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
                    logSinkCostSplit();
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
        logSinkCostSplit();
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

    /**
     * Where the sink's time actually goes. Three very different problems hide
     * behind "the sink is slow": queueing for a pooled connection, executing
     * the statement, and committing.
     */
    private void logSinkCostSplit() {
        long n = snapshotWriter.batches();
        if (n == 0) {
            return;
        }
        long rows = metrics.out("sink");
        log.info("sink cost | batches={} rows={} rowsPerBatch={} | avg per batch: "
                 + "poolWait={}us execute={}us commit={}us",
                n, rows, rows / n,
                snapshotWriter.connectionWaitNanos() / 1000 / n,
                snapshotWriter.executeNanos() / 1000 / n,
                snapshotWriter.commitNanos() / 1000 / n);
    }

    /** Visible for tests and for the shutdown/report logging. */
    public PipelineMetrics metrics() { return metrics; }

    public List<Pipe<?>> pipes() { return allPipes; }
}
