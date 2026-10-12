package com.selfcheckout.self_checkout.transactions.result;

import java.math.BigDecimal;

// TRANSACTIONS layer, result record. The result of one beep: what was scanned
// and where the basket stands now.
public record ScanOutcome(
        String transactionId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int itemCount,
        BigDecimal runningTotal
) {}
