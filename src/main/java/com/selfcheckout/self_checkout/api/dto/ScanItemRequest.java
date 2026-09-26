package com.selfcheckout.self_checkout.api.dto;

// API layer, wire shape (matches the OpenAPI spec).
// Request body for POST /transactions/{id}/items.
// One call = one physical unit scanned. To scan the same item twice,
// the client calls the endpoint twice.
public record ScanItemRequest(String sku) {}
