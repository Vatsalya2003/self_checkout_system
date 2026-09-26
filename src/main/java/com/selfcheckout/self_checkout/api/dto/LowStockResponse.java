package com.selfcheckout.self_checkout.api.dto;

import java.time.Instant;
import java.util.List;

// API layer, wire shape (matches the OpenAPI spec).
// Response for GET /inventory/low-stock.
// threshold echoes back whichever value was used: the configured default, or
// the ?threshold= override if the caller passed one.
public record LowStockResponse(int threshold, Instant generatedAt, List<LowStockAlertDto> alerts) {}
