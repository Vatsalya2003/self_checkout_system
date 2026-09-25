package com.selfcheckout.self_checkout.dto;

import java.math.BigDecimal;
import java.time.Instant;

// The "Transaction" shape from the spec, returned by POST /transactions (201)
// and GET /transactions/{id} (200).
// Careful: the JSON field is "transactionId", but the entity's field is just
// "id". The mapping happens in TransactionService.toResponse().
// Instant serializes to an ISO-8601 string like "2026-09-24T07:29:53.299835Z".
public record TransactionResponse(
        String transactionId,
        String stationId,
        String status,          // OPEN / COMPLETED / CANCELLED, as text
        int itemCount,          // units scanned so far
        BigDecimal runningTotal,
        Instant startedAt
) {}
