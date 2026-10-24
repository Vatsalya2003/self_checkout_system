package com.selfcheckout.self_checkout.remote;

import java.time.Instant;

// CHECKOUT-SERVICE, remote layer. One numbered scan, as it is pushed to
// analytics-service's POST /internal/scans. (Week 4 inserted these rows into
// a shared scan_log table; Week 5 sends them to analytics, which stores them
// in its own database.)
public record ScanLogRow(long seq, String sku, Instant scannedAt) {}
