package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// ANALYTICS-SERVICE, persistence layer. Reads the scan hand-off table.
//
// scan_log is written by checkout-service and only READ here. That table is
// the whole connection between the two services: checkout appends every scan
// with a gapless sequence number, and analytics reads them back in that order.
public interface ScanLogReader {

    /** Up to limit rows with seq > afterSeq, in seq order. */
    List<ScanLogRow> after(long afterSeq, int limit);

    /** Rows with fromExclusive < seq <= toInclusive, in seq order (window restore on restart). */
    List<ScanLogRow> between(long fromExclusive, long toInclusive);

    /** Highest seq written so far, or 0 if none -- used only to report analytics lag. */
    long maxSeq();
}
