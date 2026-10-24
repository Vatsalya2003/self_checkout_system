package com.selfcheckout.self_checkout.persistence.repository;

import java.util.List;

// CATALOG-CLIENT library, persistence layer (data access).
//
// The catalog -- sku, name, price -- held in memory.
//
// Safe to cache because the catalog is immutable for the life of a run:
// db/catalog/data.sql seeds 2000 items and nothing ever inserts, updates or
// deletes one afterwards. Stock is the part that changes, and stock is not in
// here (since Week 5 it is not even in the same database).
//
// Two implementations: catalog-service's JdbcCatalogCache reads its own
// database; every other service uses HttpCatalogCache, which loads the same
// list from catalog-service's GET /items.
public interface CatalogCache {

    /** The item, or null if no such SKU. */
    CatalogEntry find(String sku);

    /** Display name for a SKU; falls back to the SKU itself if unknown. */
    String nameOf(String sku);

    /** Every item, SKU ascending -- the order GET /items must return. */
    List<CatalogEntry> all();
}
