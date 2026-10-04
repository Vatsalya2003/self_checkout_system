package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogBatchWriter;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;

import java.util.ArrayList;
import java.util.List;

/**
 * FILTER 5 of 5 -- the scan_log writer.
 *
 * This is the filter that actually moves the needle on throughput. In Week 2
 * every scan did its own INSERT inside the request's transaction, so the
 * request paid for it and it competed for the same connection pool as
 * checkout. Here the rows are batched and written on a background thread, so
 * a scan request does no database work for analytics at all.
 *
 * The batching itself lives in BatchingLoop, which this filter shares with
 * the sink: it writes whatever has accumulated, up to a cap. When traffic is
 * light that is one or two rows; if the writer ever falls behind -- say it
 * waited for a pooled connection under stress -- the backlog is simply
 * written in bigger batches, so it catches up instead of drifting further
 * behind.
 */
public class ScanLogWriterFilter {

    private final ScanLogBatchWriter writer;

    public ScanLogWriterFilter(ScanLogBatchWriter writer) {
        this.writer = writer;
    }

    /** Write one batch. Returns how many rows were written. */
    public int flush(List<SequencedScanEvent> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<ScanLogRow> rows = new ArrayList<>(batch.size());
        for (SequencedScanEvent e : batch) {
            // map the analytics message onto the persistence-owned row type,
            // so persistence never has to know this pipeline exists
            rows.add(new ScanLogRow(e.seq(), e.sku(), e.scannedAt()));
        }
        writer.insertBatch(rows);
        return rows.size();
    }
}
