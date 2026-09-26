package com.selfcheckout.self_checkout.transactions.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// TRANSACTIONS layer, result record. A paid-for basket.
public record ReceiptView(
        String transactionId,
        String stationId,
        int itemCount,
        BigDecimal totalAmount,
        Instant startedAt,
        Instant completedAt,
        List<ReceiptLineView> lines
) {}
