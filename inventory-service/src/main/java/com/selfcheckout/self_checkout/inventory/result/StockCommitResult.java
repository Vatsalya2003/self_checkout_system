package com.selfcheckout.self_checkout.inventory.result;

import java.util.List;

// INVENTORY-SERVICE, business layer, result record: what a stock commit did.
//
// replayed = true means this transaction id had already been committed and
// these are the stored lines from that first commit -- no stock moved this
// time. checkout counts those (they are its retries and recoveries landing).
public record StockCommitResult(String transactionId, boolean replayed, List<Line> lines) {

    /** One SKU: units asked for, and units actually taken (smaller only if it sold out). */
    public record Line(String sku, int quantity, int applied) {}
}
