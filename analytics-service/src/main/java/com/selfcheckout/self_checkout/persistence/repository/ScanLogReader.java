package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// ANALYTICS-SERVICE, persistence layer. Reads the scan hand-off table.
//
// Week 5: scan_log is analytics' own table in analytics_db. JdbcScanStore
// fills it with the batches checkout-service pushes (gapless seq, in order);
// this reader hands them to the pipeline in exactly that order.
public interface ScanLogReader {

    /** Up to limit rows with seq > afterSeq, in seq order. */
    List<ScanLogRow> after(long afterSeq, int limit);

    /** Rows with fromExclusive < seq <= toInclusive, in seq order (window restore on restart). */
    List<ScanLogRow> between(long fromExclusive, long toInclusive);

    /** Highest seq written so far, or 0 if none -- used only to report analytics lag. */
    long maxSeq();
}
