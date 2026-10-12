package com.selfcheckout.self_checkout.api.dto;

import java.time.Instant;

// INVENTORY-SERVICE, API layer, wire shape (matches the OpenAPI spec).
// One low-stock alert in the GET /inventory/low-stock response.
//
// Two different timestamps are easy to mix up:
//   triggeredAt = when this SKU actually crossed below the threshold
//                 (read from the low_stock_alerts table)
//   generatedAt = when you asked the question (on LowStockResponse)
// If a SKU only qualifies because of an ad-hoc ?threshold and never really
// crossed, it has no genuine trigger time, so we fall back to generatedAt.
public record LowStockAlertDto(
        String sku,
        String name,
        int currentStock,
        int threshold,        // the threshold this query used
        Instant triggeredAt
) {}
