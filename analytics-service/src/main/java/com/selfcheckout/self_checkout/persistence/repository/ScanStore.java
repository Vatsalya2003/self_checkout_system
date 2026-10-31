package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// ANALYTICS-SERVICE, persistence layer. Where published scans are stored:
// this service's own scan_log (and scan_batches), in analytics_db.
public interface ScanStore {

    /**
     * Take the numbering lock for the rest of the current transaction, so only
     * one transaction at a time ever assigns seqs (even if a second consumer
     * were started by mistake).
     */
    void lockNumbering();

    /** The highest stored seq, or 0 if there are none. */
    long maxSeq();

    /**
     * Record that this batch is being stored as seqs first..last. Returns
     * false if the batch id is already there -- a redelivered batch.
     */
    boolean claimBatch(String batchId, long firstSeq, long lastSeq);

    /** Insert these already-numbered rows in one multi-row INSERT. */
    void insert(List<ScanLogRow> rows);
}
