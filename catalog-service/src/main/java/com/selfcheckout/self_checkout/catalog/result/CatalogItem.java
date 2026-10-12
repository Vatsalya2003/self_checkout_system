package com.selfcheckout.self_checkout.catalog.result;

import java.math.BigDecimal;

// CATALOG-SERVICE, business layer, result record.
// What the business layer knows about a catalog item. Looks almost identical
// to api.dto.ItemDto, and that duplication is the price of the layering: the
// api layer can change the wire shape without the business layer noticing,
// and the business layer never has to import an api type.
public record CatalogItem(String sku, String name, BigDecimal price) {}
