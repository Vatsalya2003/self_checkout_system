package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.SnapshotRow;
import com.selfcheckout.self_checkout.persistence.repository.SnapshotWriter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * FILTER 4 of 5 -- the sink.
 *
 * Writes computed windows to popular_snapshots. The ranked list is stored as
 * JSON because it is written once, read whole, and never queried by field.
 *
 * Batched, like the scan-log writer -- BatchingLoop decides how big a batch
 * is, this class only knows how to write one. One window per commit looked harmless --
 * windows only arrive every 500 scans -- but once checkout sped up to ~8,900
 * scans/sec that was ~18 commits/sec of work the sink could not keep up with,
 * and sinkPipe filled, which backed up rank and then the window stage. Writing
 * whatever has accumulated in one statement means a backlog makes the next
 * write cheaper per row instead of more expensive.
 */
public class SinkFilter {

    private final SnapshotWriter writer;
    private final ObjectMapper objectMapper;

    public SinkFilter(SnapshotWriter writer, ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    /** Write one batch of windows. Returns how many rows were written. */
    public int flush(List<RankedWindow> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<SnapshotRow> rows = new ArrayList<>(batch.size());
        for (RankedWindow w : batch) {
            rows.add(new SnapshotRow(w.windowStart(), w.windowEnd(), w.computedAt(),
                    objectMapper.writeValueAsString(w.items())));
        }
        writer.insertBatch(rows);
        return rows.size();
    }
}
