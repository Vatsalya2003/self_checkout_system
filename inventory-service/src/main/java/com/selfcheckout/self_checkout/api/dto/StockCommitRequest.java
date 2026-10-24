package com.selfcheckout.self_checkout.api.dto;

import java.util.List;

// INVENTORY-SERVICE, API layer. Body of POST /internal/stock/commit -- an
// INTERNAL endpoint: checkout-service calls it directly on :8083, and the
// gateway refuses every /internal/** path from outside.
public record StockCommitRequest(String transactionId, List<Line> lines) {
    public record Line(String sku, int quantity) {}
}
