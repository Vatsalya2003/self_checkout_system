package com.selfcheckout.self_checkout.api.dto;

import java.util.List;

// INVENTORY-SERVICE, API layer. Answer to POST /internal/stock/commit, sent
// only after the stock commit is durable. replayed = true: this id had already
// been committed, and these are that commit's stored lines.
public record StockCommitResponse(String transactionId, boolean replayed, List<Line> lines) {
    public record Line(String sku, int quantity, int applied) {}
}
