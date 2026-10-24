package com.selfcheckout.self_checkout.remote;

// CHECKOUT-SERVICE, remote layer. analytics-service refused a batch because
// its first seq is beyond what analytics has stored + 1 (409 SCAN_GAP). Only
// possible if analytics' data was reset while checkout kept running; the
// publisher then renumbers the batch from storedMax + 1.
public class ScanGapRejectedException extends RuntimeException {
    private final long storedMax;

    public ScanGapRejectedException(long storedMax) {
        super("analytics has only 1.." + storedMax);
        this.storedMax = storedMax;
    }

    public long storedMax() {
        return storedMax;
    }
}
