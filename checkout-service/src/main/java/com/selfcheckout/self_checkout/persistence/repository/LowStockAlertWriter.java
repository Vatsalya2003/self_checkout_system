package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;
import java.util.List;

// CHECKOUT-SERVICE, persistence layer.
// Records SKUs that have just crossed below the low-stock threshold.
public interface LowStockAlertWriter {

    /**
     * Insert one alert per SKU, in one statement.
     *
     * Crossings are rare -- a SKU crosses once per run, because stock is never
     * replenished -- so most checkouts call this with an empty list and it
     * sends nothing at all.
     */
    void insertCrossings(List<String> skus, Instant triggeredAt);
}
