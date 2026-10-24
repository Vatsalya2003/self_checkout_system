package com.selfcheckout.self_checkout.api.dto;

import java.time.Instant;
import java.util.List;

// ANALYTICS-SERVICE, API layer. Body of POST /internal/scans (internal: only
// checkout-service calls it, directly on :8084; the gateway refuses
// /internal/**). One batch of consecutive, already-numbered scans.
public record ScanBatch(List<Scan> scans) {
    public record Scan(long seq, String sku, Instant scannedAt) {}
}
