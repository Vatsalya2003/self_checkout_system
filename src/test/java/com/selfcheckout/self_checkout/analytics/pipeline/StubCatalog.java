package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.CatalogEntry;

import java.math.BigDecimal;
import java.util.List;

/** Test double: names a SKU without touching a database. */
final class StubCatalog implements CatalogCache {
    private final String prefix;
    StubCatalog(String prefix) { this.prefix = prefix; }
    @Override public CatalogEntry find(String sku) { return new CatalogEntry(sku, nameOf(sku), BigDecimal.ONE); }
    @Override public String nameOf(String sku) { return prefix + sku; }
    @Override public List<CatalogEntry> all() { return List.of(); }
}
