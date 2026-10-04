package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogBatchWriter;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScanLogWriterFilterTest {

    /** Records every batch it is handed, so the test can assert on batching. */
    private static final class RecordingWriter implements ScanLogBatchWriter {
        final List<List<ScanLogRow>> batches = new ArrayList<>();
        @Override public void insertBatch(List<ScanLogRow> rows) { batches.add(List.copyOf(rows)); }
    }

    private static SequencedScanEvent ev(long seq) {
        return new SequencedScanEvent(seq, "SKU-" + seq, Instant.EPOCH);
    }

    @Test
    @DisplayName("writes the whole batch in one call and maps every field")
    void writesOneBatch() {
        RecordingWriter w = new RecordingWriter();
        ScanLogWriterFilter f = new ScanLogWriterFilter(w, 1000);

        int written = f.flush(List.of(ev(1), ev(2), ev(3)));

        assertEquals(3, written);
        assertEquals(1, w.batches.size(), "one INSERT, not three");
        assertEquals(List.of(1L, 2L, 3L), w.batches.get(0).stream().map(ScanLogRow::seq).toList());
        assertEquals("SKU-2", w.batches.get(0).get(1).sku());
        assertEquals(Instant.EPOCH, w.batches.get(0).get(1).scannedAt());
    }

    @Test
    @DisplayName("an empty batch writes nothing at all")
    void emptyBatchIsNoOp() {
        RecordingWriter w = new RecordingWriter();
        assertEquals(0, new ScanLogWriterFilter(w, 1000).flush(List.of()));
        assertTrue(w.batches.isEmpty());
    }

    @Test
    @DisplayName("the loop batches what is queued, and flushes a partial batch when idle")
    void loopBatchesAndFlushesOnIdle() throws Exception {
        RecordingWriter w = new RecordingWriter();
        PipelineMetrics metrics = new PipelineMetrics();
        metrics.registerStage("scanlog");
        Pipe<SequencedScanEvent> pipe = new BlockingQueuePipe<>("scanLogPipe", 1000);

        // 250 events are already waiting before the loop starts, so the first
        // sweep should pick up far more than one.
        for (int i = 1; i <= 250; i++) pipe.put(ev(i));

        Thread t = new Thread(new ScanLogWriterLoop("scanlog", pipe,
                new ScanLogWriterFilter(w, 1000), metrics));
        t.start();
        Thread.sleep(400);          // let it drain
        pipe.poison();
        t.join(5000);

        int rows = w.batches.stream().mapToInt(List::size).sum();
        assertEquals(250, rows, "every queued row must be written exactly once");
        assertTrue(w.batches.get(0).size() > 1, "the first sweep should batch, not write one row");
        assertEquals(250, metrics.out("scanlog"), "metrics count ROWS written");
    }

    @Test
    @DisplayName("batches are capped at maxBatch even when more is queued")
    void respectsMaxBatch() throws Exception {
        RecordingWriter w = new RecordingWriter();
        PipelineMetrics metrics = new PipelineMetrics();
        metrics.registerStage("scanlog");
        Pipe<SequencedScanEvent> pipe = new BlockingQueuePipe<>("scanLogPipe", 1000);
        for (int i = 1; i <= 500; i++) pipe.put(ev(i));

        Thread t = new Thread(new ScanLogWriterLoop("scanlog", pipe,
                new ScanLogWriterFilter(w, 100), metrics));
        t.start();
        Thread.sleep(500);
        pipe.poison();
        t.join(5000);

        assertEquals(500, w.batches.stream().mapToInt(List::size).sum());
        assertTrue(w.batches.stream().allMatch(b -> b.size() <= 100), "no batch may exceed maxBatch");
    }

    @Test
    @DisplayName("anything still queued at shutdown is flushed, not lost")
    void flushesAtShutdown() throws Exception {
        RecordingWriter w = new RecordingWriter();
        PipelineMetrics metrics = new PipelineMetrics();
        metrics.registerStage("scanlog");
        Pipe<SequencedScanEvent> pipe = new BlockingQueuePipe<>("scanLogPipe", 1000);

        Thread t = new Thread(new ScanLogWriterLoop("scanlog", pipe,
                new ScanLogWriterFilter(w, 1000), metrics));
        t.start();
        Thread.sleep(150);                       // loop is idle, polling
        for (int i = 1; i <= 7; i++) pipe.put(ev(i));
        pipe.poison();                           // poison immediately after
        t.join(5000);

        assertEquals(7, w.batches.stream().mapToInt(List::size).sum(),
                "the tail of the stream must still reach the database");
    }
}
