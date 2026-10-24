package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// ANALYTICS-SERVICE, persistence layer. Where pushed scans are stored: this
// service's own copy of scan_log, in analytics_db.
public interface ScanStore {

    /**
     * Insert these rows in one multi-row INSERT, skipping any seq already
     * stored (a retried batch). Returns how many rows were new.
     */
    int insertNew(List<ScanLogRow> rows);

    /** The highest stored seq, or 0 if there are none. */
    long maxSeq();
}
