package com.selfcheckout.self_checkout.remote;

import java.util.Map;

// CHECKOUT-SERVICE, remote layer. The one call checkout makes to
// inventory-service: commit the stock for a payment. Idempotent on
// transactionId -- sending the same id and lines again is always safe.
public interface InventoryClient {

    /** One attempt. Never throws for a network problem; reports it as an outcome instead. */
    StockCommitOutcome commit(String transactionId, Map<String, Integer> quantityBySku);
}
