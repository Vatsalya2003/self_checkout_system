package com.selfcheckout.self_checkout.inventory.result;

import java.time.Instant;

// INVENTORY-SERVICE, business layer, result record. One SKU that is running low.
// triggeredAt is the real crossing time when one was recorded; for a SKU that
// only qualifies under an ad-hoc threshold it falls back to the query time.
public record LowStockEntry(
        String sku,
        String name,
        int currentStock,
        int threshold,
        Instant triggeredAt
) {}
