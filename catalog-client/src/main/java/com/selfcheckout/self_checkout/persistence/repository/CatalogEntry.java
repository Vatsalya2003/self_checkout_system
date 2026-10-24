package com.selfcheckout.self_checkout.persistence.repository;

import java.math.BigDecimal;

// CATALOG-CLIENT library, persistence layer (data access).
// One catalog item as everything above persistence needs it: identity, display
// name, price. Deliberately NOT the Item entity -- stock is missing on purpose,
// because stock changes constantly and must never be served from a cache.
public record CatalogEntry(String sku, String name, BigDecimal price) {}
