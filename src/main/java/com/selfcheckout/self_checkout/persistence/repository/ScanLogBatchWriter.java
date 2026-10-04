package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// PERSISTENCE layer (database access).
// Writes many scan_log rows in one statement.
//
// An interface, not just a class, for two reasons: the analytics filter that
// uses it can be unit-tested with a two-line stub and no database, and the
// batching strategy can change without the filter noticing.
public interface ScanLogBatchWriter {

    /** Insert every row in one multi-row INSERT, in one transaction. */
    void insertBatch(List<ScanLogRow> rows);
}
