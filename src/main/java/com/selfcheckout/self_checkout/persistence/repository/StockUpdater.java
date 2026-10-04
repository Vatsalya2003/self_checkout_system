package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;
import java.util.Map;

// PERSISTENCE layer (database access).
//
// The two statements that do the actual stock work for a checkout: lock every
// item the basket needs, then write every new stock value. Both are bulk, so
// the number of round trips stops growing with basket size.
public interface StockUpdater {

    /**
     * Lock every one of these SKUs and return their current stock.
     *
     * One statement, not one per SKU. The rows are still locked in ascending
     * SKU order, which is what keeps checkouts deadlock-free.
     */
    Map<String, Integer> lockAndReadStock(List<String> skusAscending);

    /** Set the new stock for every SKU, in one statement. */
    void applyStock(Map<String, Integer> newStockBySku);
}
