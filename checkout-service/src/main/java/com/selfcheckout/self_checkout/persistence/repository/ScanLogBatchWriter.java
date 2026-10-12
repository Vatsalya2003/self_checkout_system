package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// CHECKOUT-SERVICE, persistence layer.
// Appends scans to scan_log -- the hand-off table analytics-service reads.
//
// An interface, not just a class, so the publisher that uses it can be
// unit-tested with a small stub and no database.
public interface ScanLogBatchWriter {

    /** Insert every row in one multi-row INSERT (one statement, so one atomic commit). */
    void insertBatch(List<ScanLogRow> rows);

    /** The highest seq already in scan_log, or 0 if it is empty. Read once at startup. */
    long maxSeq();
}
