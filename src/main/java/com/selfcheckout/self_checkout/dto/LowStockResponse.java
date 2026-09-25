package com.selfcheckout.self_checkout.dto;

import java.time.Instant;
import java.util.List;

// Response for GET /inventory/low-stock.
// threshold echoes back whichever value was used: the configured default, or
// the ?threshold= override if the caller passed one.
public record LowStockResponse(int threshold, Instant generatedAt, List<LowStockAlertDto> alerts) {}
