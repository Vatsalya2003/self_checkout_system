package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;

// ANALYTICS-SERVICE, persistence layer.
// One scan_log row as analytics reads it back. A persistence-owned type, so
// the persistence layer never depends on the pipeline's own message records
// (the architecture test forbids that direction).
public record ScanLogRow(long seq, String sku, Instant scannedAt) {}
