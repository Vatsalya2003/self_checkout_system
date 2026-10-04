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
     * No @Transactional here, deliberately. Scanning no longer opens a
     * database transaction at all -- the basket lives in memory until it is
     * paid for -- so there is nothing to join and nothing to wait for. If a
     * caller DOES happen to have a transaction open, this still defers until
     * that transaction commits, so a rolled-back caller never reaches
     * analytics.
     */
    void recordScan(String sku);
}
