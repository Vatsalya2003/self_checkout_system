package com.selfcheckout.self_checkout.api.dto;

// ANALYTICS-SERVICE, API layer, wire shape (matches the OpenAPI spec).
// One row of the popular-items ranking.
// rank is 1-based (1 = most scanned), because that's what the spec shows.
// These get stored as JSON inside popular_snapshots.items_json, so this record
// doubles as the on-disk format -- renaming a field would break old snapshots.
public record PopularItem(String sku, String name, long scanCount, int rank) {}
