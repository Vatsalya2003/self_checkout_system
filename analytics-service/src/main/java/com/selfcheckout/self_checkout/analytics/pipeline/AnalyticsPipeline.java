package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.entity.PopularSnapshot;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.PopularSnapshotRepository;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogReader;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import com.selfcheckout.self_checkout.persistence.repository.SnapshotWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * ANALYTICS-SERVICE. Builds the pipeline and owns its threads.
 *
 * Shape (see PIPELINE.md and SERVICES.md):
 *
 *   scan_log (written by checkout-service)
 *      --poll--> [poller] --put--> [window] --put--> [rank] --put--> [sink] --> popular_snapshots
 *
 * One thread per filter, a bounded queue between each pair, and every pipe a
 * blocking put: nothing inside analytics is ever dropped. If analytics falls
 * behind, the rows simply wait in scan_log -- which is now durable, so a
 * backlog costs lag, not data.
 *
 * Week 4 change: the source is no longer an in-process ingest filter fed by
 * the request thread (that thread now lives in another process). The poller
 * reads scan_log in seq order instead. The window, rank and sink filters are
 * unchanged from Week 3.
 */
@Component
public class AnalyticsPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPipeline.class);

    /** The assignment requires the top 10 to be written to the database. */
    private static final int TOP_N = 10;

    // The event pipe carries one message per scan: 65536 slots is several
    // seconds of buffer at stress rates. The poller fills it with blocking
    // puts, so if the window filter is slow the poller simply reads later.
    private static final int EVENT_PIPE_CAPACITY = 65_536;
    // One message per 500 scans; generous headroom, as in Week 3.
    private static final int WINDOW_PIPE_CAPACITY = 4_096;
    // 512 rows x 4 columns = 2048 bind parameters per sink INSERT.
    private static final int MAX_SNAPSHOT_BATCH = 512;

    private static final long METRICS_INTERVAL_MILLIS = 10_000;
    private static final long LAG_SAMPLE_MILLIS = 1_000;

    private final SelfCheckoutProperties properties;
    private final ScanLogReader scanLogReader;
    private final PopularSnapshotRepository snapshots;
    private final SnapshotWriter snapshotWriter;
    private final CatalogCache catalogNames;
    private final ObjectMapper objectMapper;

    private final PipelineMetrics metrics = new PipelineMetrics();
    private final LagStats lag = new LagStats();

    private Pipe<SequencedScanEvent> windowPipe;
    private Pipe<WindowCounts> rankPipe;
    private Pipe<RankedWindow> sinkPipe;
    private List<Pipe<?>> allPipes = List.of();
    private ScanLogPoller poller;

    private final List<Thread> threads = new ArrayList<>();
    private volatile Thread metricsThread;

    public AnalyticsPipeline(SelfCheckoutProperties properties,
                             ScanLogReader scanLogReader,
                             PopularSnapshotRepository snapshots,
                             SnapshotWriter snapshotWriter,
                             CatalogCache catalogNames,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.scanLogReader = scanLogReader;
        this.snapshots = snapshots;
        this.snapshotWriter = snapshotWriter;
        this.catalogNames = catalogNames;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        windowPipe = new BlockingQueuePipe<>("windowPipe", EVENT_PIPE_CAPACITY);
        rankPipe   = new BlockingQueuePipe<>("rankPipe",   WINDOW_PIPE_CAPACITY);
        sinkPipe   = new BlockingQueuePipe<>("sinkPipe",   WINDOW_PIPE_CAPACITY);
        allPipes = List.of(windowPipe, rankPipe, sinkPipe);
        for (String s : List.of("poller", "window", "rank", "sink")) {
            metrics.registerStage(s);
        }

        WindowFilter window = new WindowFilter(properties.popularWindowSize(),
                                               properties.popularSlideInterval());
        long resumeAfter = restoreWindow(window);

        RankFilter rank = new RankFilter(TOP_N, catalogNames);
        SinkFilter sink = new SinkFilter(snapshotWriter, objectMapper);
        poller = new ScanLogPoller(scanLogReader, windowPipe, metrics, resumeAfter);

        startThread("pipeline-poller", poller);

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

        startMetricsReporter();
        log.info("analytics pipeline started: poller -> window -> rank -> sink, reading scan_log after seq {}",
                resumeAfter);
    }

    /**
     * After a restart, put the window filter back exactly where the last
     * stored window left it: reload that window's scans from scan_log, then
     * resume polling at the very next seq.
     *
     * The window filter emits on every multiple of slideInterval, so the
     * replayed boundary would emit the stored window a second time; that
     * output is discarded on purpose (popular_snapshots has a UNIQUE
     * window_end and the window already exists).
     *
     * On a freshly reset database there is no window yet, and this is a no-op.
     */
    private long restoreWindow(WindowFilter window) {
        long lastEnd = snapshots.findFirstByOrderByWindowEndDesc()
                .map(PopularSnapshot::getWindowEnd).orElse(0L);
        if (lastEnd == 0) {
            return 0;
        }
        long from = Math.max(0, lastEnd - properties.popularWindowSize());
        List<ScanLogRow> rows = scanLogReader.between(from, lastEnd);
        for (ScanLogRow r : rows) {
            window.accept(new SequencedScanEvent(r.seq(), r.sku(), r.scannedAt()));   // output ignored
        }
        if (rows.size() != lastEnd - from) {
            log.warn("window restore read {} scan_log rows for ({}, {}], expected {}",
                    rows.size(), from, lastEnd, lastEnd - from);
        }
        log.info("restored the window from scan_log: {} rows in ({}, {}]", rows.size(), from, lastEnd);
        return lastEnd;
    }

    private void startThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);      // a wedged stage must never keep the JVM alive
        t.start();
        threads.add(t);
    }

    /**
     * Every second: how far behind scan_log the poller is, in scans. Every ten
     * seconds: one log line with the pipeline counters and the lag. These are
     * the numbers the load-test write-up quotes as "analytics lag".
     */
    private void startMetricsReporter() {
        Thread t = new Thread(() -> {
            long lastReport = System.currentTimeMillis();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(LAG_SAMPLE_MILLIS);
                    try {
                        lag.sample(scanLogReader.maxSeq() - poller.lastSeq());
                    } catch (RuntimeException e) {
                        log.debug("lag sample failed", e);
                    }
                    if (System.currentTimeMillis() - lastReport >= METRICS_INTERVAL_MILLIS) {
                        lastReport = System.currentTimeMillis();
                        log.info(metrics.summary(allPipes));
                        log.info(lagLine());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-metrics");
        t.setDaemon(true);
        t.start();
        metricsThread = t;
    }

    private String lagLine() {
        return String.format("analytics lag | lastSeq=%d | behind scan_log now=%d max=%d mean=%d scans "
                        + "| read lag mean=%d max=%d ms | gapStalls=%d",
                poller.lastSeq(), lag.last(), lag.max(), lag.mean(),
                poller.meanReadLagMillis(), poller.maxReadLagMillis(), poller.gapStalls());
    }

    /**
     * Stop in order: stop the poller, which poisons the window pipe, and let
     * each stage drain and poison the next. Nothing is lost by stopping: rows
     * the poller had not read yet are still in scan_log, and the next start
     * resumes from the last stored window.
     */
    @PreDestroy
    public void stop() {
        if (poller != null) {
            poller.stop();
        }
        for (Thread t : threads) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (metricsThread != null) {
            metricsThread.interrupt();
        }
        log.info("analytics pipeline stopped: {}", metrics.summary(allPipes));
        log.info(lagLine());
    }

    /** Visible for tests and for the shutdown/report logging. */
    public PipelineMetrics metrics() { return metrics; }

    public List<Pipe<?>> pipes() { return allPipes; }

    /** Running max and mean of a sampled value, written by one thread. */
    static final class LagStats {
        private volatile long last, max, total, samples;

        void sample(long v) {
            last = v;
            if (v > max) {
                max = v;
            }
            total += v;
            samples++;
        }

        long last() { return last; }
        long max()  { return max; }
        long mean() { return samples == 0 ? 0 : total / samples; }
    }
}
