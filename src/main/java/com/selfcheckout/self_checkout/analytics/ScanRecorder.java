package com.selfcheckout.self_checkout.analytics;

/**
 * ANALYTICS layer. The one door the transactions layer is allowed to walk
 * through to reach analytics.
 *
 * This interface is deliberately tiny. The transactions layer needs to know
 * exactly one thing -- "a unit of this SKU was just scanned" -- and nothing
 * about scan sequence numbers, window sizes, boundary arithmetic or background
 * threads. All of that lives behind here and can be rewritten without the
 * checkout code noticing.
 *
 * It also keeps the dependency one-directional: transactions depends on
 * analytics, analytics never depends on transactions.
 */
public interface ScanRecorder {

    /**
     * Record one scanned unit.
     *
     * Runs inside the caller's database transaction (no @Transactional here,
     * so it simply joins the one already in progress). That matters: if the
     * scan rolls back, the scan_log row must roll back with it.
     */
    void recordScan(String sku);
}
