package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.SnapshotWriter;
import tools.jackson.databind.ObjectMapper;

/**
 * FILTER 4 of 5 -- the sink.
 *
 * Writes one popular_snapshots row per window. The ranked list is stored as
 * JSON because it is written once, read whole, and never queried by field.
 *
 * Note what this does NOT do: swallow a duplicate. popular_snapshots has a
 * UNIQUE constraint on window_end, and only one thread ever produces window
 * boundaries, so a duplicate could only mean a bug in the pipeline. Letting it
 * throw means the filter loop logs and counts it; ON CONFLICT DO NOTHING would
 * hide precisely the regression the constraint is there to catch.
 */
public class SinkFilter {

    private final SnapshotWriter writer;
    private final ObjectMapper objectMapper;

    public SinkFilter(SnapshotWriter writer, ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    public void accept(RankedWindow window) {
        writer.insert(window.windowStart(), window.windowEnd(), window.computedAt(),
                objectMapper.writeValueAsString(window.items()));
    }
}
