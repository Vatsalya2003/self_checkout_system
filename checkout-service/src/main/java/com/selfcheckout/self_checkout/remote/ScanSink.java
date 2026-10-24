package com.selfcheckout.self_checkout.remote;

import java.util.List;

// CHECKOUT-SERVICE, remote layer. Where numbered scans go: analytics-service.
// An interface so the publisher (scanlog layer) is unit-tested with a fake and
// never sees HTTP.
public interface ScanSink {

    /**
     * Deliver one batch of consecutive seqs. Returns only once analytics has
     * stored it durably. Throws ScanGapRejectedException if analytics says the
     * batch would leave a hole; any other exception means "not known to be
     * delivered -- send the same batch again".
     */
    void push(List<ScanLogRow> rows);

    /** The highest seq analytics has stored: where numbering continues after a restart. */
    long maxSeq();
}
