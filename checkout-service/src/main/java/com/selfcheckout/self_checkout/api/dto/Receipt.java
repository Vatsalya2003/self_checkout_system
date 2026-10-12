package com.selfcheckout.self_checkout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// CHECKOUT-SERVICE, API layer, wire shape (matches the OpenAPI spec).
// Response for POST /transactions/{id}/complete.
// totalAmount is just the transaction's runningTotal at completion time -- we
// don't re-add the lines, so the receipt total can never disagree with what
// the customer saw on screen while scanning.
public record Receipt(
        String transactionId,
        String stationId,
        int itemCount,
        BigDecimal totalAmount,
        Instant startedAt,
        Instant completedAt,
        List<ReceiptLine> lines
) {}
