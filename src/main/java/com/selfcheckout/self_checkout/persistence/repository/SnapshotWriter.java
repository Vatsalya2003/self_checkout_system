package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// PERSISTENCE layer (database access).
// Writes computed popular-items windows.
public interface SnapshotWriter {

    /**
     * Insert every window in one statement and one commit.
     *
     * Batched for the same reason the scan-log writer is: when the pipeline
     * falls behind, the backlog should make the next write cheaper per row,
     * not more expensive.
     *
     * There is a UNIQUE constraint on window_end and this does NOT swallow a
     * violation. Exactly one thread produces window boundaries, so a duplicate
     * could only mean a bug.
     */
    void insertBatch(List<SnapshotRow> rows);

    /** Nanoseconds spent waiting for a pooled connection, accumulated. */
    long connectionWaitNanos();

    /** Nanoseconds spent executing the insert, accumulated. */
    long executeNanos();

    /** Nanoseconds spent in commit, accumulated. */
    long commitNanos();

    /** How many batches have been written. */
    long batches();
}
