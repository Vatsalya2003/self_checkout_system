package com.selfcheckout.self_checkout.dto;

// Request body for POST /transactions/{id}/items.
// One call = one physical unit scanned. To scan the same item twice,
// the client calls the endpoint twice.
public record ScanItemRequest(String sku) {}
