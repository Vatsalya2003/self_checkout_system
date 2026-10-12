package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;

// INVENTORY-SERVICE, persistence layer (read-only).
/**
 * Query projection for the low-stock join. Deliberately does not carry the
 * threshold: that value is the same for every row, so the service stamps it
 * on rather than the database echoing a constant back 2000 times.
 */
// There's also a practical reason: Hibernate could not work out the type of a
// bare :threshold parameter sitting inside a "select new ..." constructor call
// and refused to start. Keeping constants out of the query sidesteps that.
public record LowStockRow(String sku, String name, int currentStock, Instant triggeredAt) {}
