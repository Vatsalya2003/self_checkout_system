package com.selfcheckout.self_checkout.api.dto;

import java.math.BigDecimal;

// CHECKOUT-SERVICE, API layer, wire shape (matches the OpenAPI spec).
// What the screen shows after each beep: the item just scanned, plus the
// running basket so far.
// itemCount and runningTotal already include the unit we just scanned.
public record ScanResult(
        String transactionId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int itemCount,
        BigDecimal runningTotal
) {}
