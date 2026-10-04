package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;

// PERSISTENCE layer (database access).
// Writes one computed popular-items window.
//
// Takes plain values rather than an analytics record, so persistence stays
// free of any dependency on the layer above it.
public interface SnapshotWriter {

    /**
     * Insert one popular_snapshots row.
     *
     * There is a UNIQUE constraint on window_end, and this does NOT swallow a
     * violation. Exactly one thread owns window boundaries, so a duplicate can
     * only mean a bug -- it should be loud, not quietly ignored.
     */
    void insert(long windowStart, long windowEnd, Instant computedAt, String itemsJson);
}
