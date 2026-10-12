package com.selfcheckout.self_checkout.transactions.result;

import java.math.BigDecimal;
import java.time.Instant;

// TRANSACTIONS layer, result record. The state of one basket.
//
// status is a plain String, NOT the TransactionStatus enum. The enum is a
// persistence type, and letting it travel up to the api layer would make the
// api depend on persistence -- exactly what the architecture test forbids.
public record TransactionView(
        String transactionId,
        String stationId,
        String status,
        int itemCount,
        BigDecimal runningTotal,
        Instant startedAt
) {}
