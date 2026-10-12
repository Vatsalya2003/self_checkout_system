package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogReader;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pushes events through the real filters on real threads over real pipes, and
 * checks every emitted window against a brute-force recount of the same range.
 *
 * Brute force here means: keep the entire event list, and for each window
 * re-count it from scratch. Slow and obviously correct -- which is the point.
 * If the incremental ring-buffer logic ever drifts, this catches it.
 *
 * Week 4: the source is now the real ScanLogPoller reading a fake scan_log
 * that a producer thread appends to in batches -- the same shape as
 * checkout-service's writer committing batch after batch while analytics
 * polls. So this also checks that the hand-off through the table loses,
 * duplicates and reorders nothing.
 */
class PipelineEndToEndTest {

    private static final int WINDOW = 1000;
    private static final int SLIDE = 500;
    private static final int TOTAL = 2600;

    /** Deliberately skewed, like the real load client, so ranks actually move. */
    private static String skuFor(int i) {
        if (i % 2 == 0) return "SKU-HOT";
        if (i % 3 == 0) return "SKU-WARM";
        return String.format("SKU-%03d", i % 40);
    }

    /** scan_log in memory: rows become visible a whole batch at a time, like a commit. */
    static final class FakeScanLog implements ScanLogReader {
        final List<ScanLogRow> rows = new CopyOnWriteArrayList<>();

        void commit(List<ScanLogRow> batch) { rows.addAll(batch); }

        @Override public List<ScanLogRow> after(long afterSeq, int limit) {
            List<ScanLogRow> out = new ArrayList<>();
            for (ScanLogRow r : rows) {
                if (r.seq() > afterSeq && out.size() < limit) out.add(r);
            }
            return out;
        }
        @Override public List<ScanLogRow> between(long from, long to) {
            List<ScanLogRow> out = new ArrayList<>();
            for (ScanLogRow r : rows) if (r.seq() > from && r.seq() <= to) out.add(r);
            return out;
        }
        @Override public long maxSeq() { return rows.isEmpty() ? 0 : rows.get(rows.size() - 1).seq(); }
    }

    @Test
    @DisplayName("every window the pipeline emits equals a brute-force recount")
    void endToEndMatchesBruteForce() throws Exception {
        List<RankedWindow> emitted = new CopyOnWriteArrayList<>();
        FakeScanLog scanLog = new FakeScanLog();

        PipelineMetrics metrics = new PipelineMetrics();
        for (String s : List.of("poller", "window", "rank", "sink")) metrics.registerStage(s);

        Pipe<SequencedScanEvent> windowPipe = new BlockingQueuePipe<>("windowPipe", 8192);
        Pipe<WindowCounts> rankPipe = new BlockingQueuePipe<>("rankPipe", 256);
        Pipe<RankedWindow> sinkPipe = new BlockingQueuePipe<>("sinkPipe", 256);

        WindowFilter window = new WindowFilter(WINDOW, SLIDE);
        RankFilter rank = new RankFilter(10, new StubCatalog("Name "));
        ScanLogPoller poller = new ScanLogPoller(scanLog, windowPipe, metrics, 0);

        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(poller, "t-poller"));

        threads.add(new Thread(new FilterLoop<>("window", windowPipe,
                e -> window.accept(e).ifPresent(c -> {
                    try { rankPipe.put(c); metrics.recordOut("window"); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }), () -> rankPipe.poison(), metrics), "t-window"));

        threads.add(new Thread(new FilterLoop<>("rank", rankPipe, c -> {
            try { sinkPipe.put(rank.rank(c)); metrics.recordOut("rank"); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }, () -> sinkPipe.poison(), metrics), "t-rank"));

        threads.add(new Thread(new FilterLoop<>("sink", sinkPipe,
                w -> { emitted.add(w); metrics.recordOut("sink"); },
                () -> { }, metrics), "t-sink"));
        threads.forEach(Thread::start);

        // The "checkout-service writer": commits uneven batches while the poller reads.
        List<String> all = new ArrayList<>(TOTAL);
        List<ScanLogRow> batch = new ArrayList<>();
        for (int i = 1; i <= TOTAL; i++) {
            String sku = skuFor(i);
            all.add(sku);
            batch.add(new ScanLogRow(i, sku, Instant.now()));
            if (batch.size() == 1 + (i % 37) || i == TOTAL) {
                scanLog.commit(batch);
                batch = new ArrayList<>();
                if (i % 5 == 0) Thread.sleep(1);
            }
        }

        long deadline = System.currentTimeMillis() + 15_000;
        while (poller.lastSeq() < TOTAL && System.currentTimeMillis() < deadline) Thread.sleep(10);
        poller.stop();
        for (Thread t : threads) t.join(15_000);
        for (Thread t : threads) assertFalse(t.isAlive(), t.getName() + " did not stop");

        // ---- every row was read exactly once, in order, with no gap stalls ----
        assertEquals(TOTAL, poller.lastSeq());
        assertEquals(TOTAL, metrics.in("poller"));
        assertEquals(TOTAL, metrics.out("poller"));
        assertEquals(TOTAL, metrics.in("window"));
        assertEquals(0, poller.gapStalls());

        // ---- windows: count, bounds, and contents ----
        assertEquals(TOTAL / SLIDE, emitted.size(), "one window per slide interval");
        for (int w = 0; w < emitted.size(); w++) {
            RankedWindow got = emitted.get(w);
            long end = (long) (w + 1) * SLIDE;
            long start = Math.max(0, end - WINDOW);
            assertEquals(end, got.windowEnd());
            assertEquals(start, got.windowStart());
            assertEquals(bruteForce(all, start, end), simplify(got.items()),
                    "window (" + start + "," + end + "] disagrees with a brute-force recount");
        }
    }

    /** Recount the range from the raw event list, the slow obvious way. */
    private static List<String> bruteForce(List<String> all, long start, long end) {
        Map<String, Long> counts = new HashMap<>();
        for (long seq = start + 1; seq <= end; seq++) {              // half-open (start, end]
            counts.merge(all.get((int) seq - 1), 1L, Long::sum);
        }
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue())
                .reversed().thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(10, ordered.size()); i++) {
            out.add(ordered.get(i).getKey() + "=" + ordered.get(i).getValue() + "#" + (i + 1));
        }
        return out;
    }

    private static List<String> simplify(List<PopularEntry> items) {
        List<String> out = new ArrayList<>();
        for (PopularEntry e : items) out.add(e.sku() + "=" + e.scanCount() + "#" + e.rank());
        return out;
    }

    @Test
    @DisplayName("a failing stage is logged and counted, and the pipeline keeps running")
    void oneBadMessageDoesNotKillAStage() throws Exception {
        PipelineMetrics metrics = new PipelineMetrics();
        metrics.registerStage("sink");
        Pipe<RankedWindow> pipe = new BlockingQueuePipe<>("sinkPipe", 16);
        List<Long> survived = new CopyOnWriteArrayList<>();

        Thread t = new Thread(new FilterLoop<>("sink", pipe, w -> {
            if (w.windowEnd() == 1000) throw new IllegalStateException("boom");
            survived.add(w.windowEnd());
        }, () -> { }, metrics));
        t.start();

        for (long end : new long[]{500, 1000, 1500}) {
            pipe.put(new RankedWindow(0, end, Instant.EPOCH, List.of()));
        }
        pipe.poison();
        t.join(5000);

        assertEquals(List.of(500L, 1500L), survived, "the stage must survive the bad message");
        assertEquals(1, metrics.errors("sink"));
        assertEquals(3, metrics.in("sink"));
    }
}
