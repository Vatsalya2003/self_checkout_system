package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// INVENTORY-SERVICE, persistence layer.
//
// Every statement inventory-service runs against its own database,
// inventory_db. An interface so the business layer never names SQL.
public interface StockLedger {

    /** One line of a stock commit: asked for, and actually taken. */
    record CommitLine(String sku, int quantity, int applied) {}

    /** One SKU below a threshold, for GET /inventory/low-stock. */
    record LowStockRow(String sku, int qty, Instant triggeredAt) {}

    /**
     * Claim this transaction id for a new commit: INSERT ... ON CONFLICT DO
     * NOTHING into stock_commits. True if this call created the row; false if
     * a commit with this id already exists (a retry), in which case nothing
     * else may be done except return the stored lines.
     */
    boolean claimCommit(String transactionId, Instant at);

    /** The lines stored by an earlier commit of this id, SKU ascending. */
    List<CommitLine> storedCommit(String transactionId);

    /**
     * Lock every one of these SKUs and return their current stock.
     * One statement, rows locked in ascending SKU order (deadlock freedom).
     */
    Map<String, Integer> lockAndReadStock(List<String> skusAscending);

    /** Set the new quantity of every SKU, in one statement. */
    void applyStock(Map<String, Integer> newQtyBySku);

    /** Record what this commit asked for and took, as one multi-row INSERT. */
    void insertLines(String transactionId, List<CommitLine> lines);

    /** Record the SKUs whose stock just dropped below the threshold. */
    void insertCrossings(List<String> skus, Instant at);

    /** Every SKU with stock strictly below the threshold, lowest stock first. */
    List<LowStockRow> findBelowThreshold(int threshold);
}
