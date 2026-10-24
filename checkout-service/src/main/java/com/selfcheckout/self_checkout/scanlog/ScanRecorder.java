package com.selfcheckout.self_checkout.scanlog;

/**
 * CHECKOUT-SERVICE, scan-log layer. The one door the transactions layer walks
 * through to publish a scan.
 *
 * Deliberately tiny: the checkout code needs to know exactly one thing -- "a
 * unit of this SKU was just scanned" -- and nothing about sequence numbers,
 * batching or who reads the scans afterwards.
 *
 * Week 4 change: in the single application this interface was implemented by
 * the analytics layer, which put the scan straight onto its in-process
 * pipeline. Analytics is now a separate service with its own database, so
 * the implementation (ScanLogPublisher) numbers the scan and pushes it to
 * analytics-service in a batch (Week 4 wrote it to a shared table instead).
 * The checkout code calling this did not change at all.
 */
public interface ScanRecorder {

    /**
     * Record one scanned unit. Never blocks the caller for more than a few
     * milliseconds and never throws: a scan must not wait on, or fail because
     * of, analytics.
     */
    void recordScan(String sku);
}
