package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// PERSISTENCE layer (database access).
//
// The catalog -- sku, name, price -- held in memory.
//
// Safe to cache because the catalog is immutable for the life of a run:
// data.sql seeds 2000 items at boot and nothing ever inserts, updates or
// deletes one afterwards. Stock is the part that changes, and stock is
// deliberately not in here.
public interface CatalogCache {

    /** The item, or null if no such SKU. */
    CatalogEntry find(String sku);

    /** Display name for a SKU; falls back to the SKU itself if unknown. */
    String nameOf(String sku);

    /** Every item, SKU ascending -- the order GET /items must return. */
    List<CatalogEntry> all();
}
