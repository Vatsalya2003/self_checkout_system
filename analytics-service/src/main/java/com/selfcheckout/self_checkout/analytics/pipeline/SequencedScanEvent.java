package com.selfcheckout.self_checkout.analytics.pipeline;

import java.time.Instant;

// ANALYTICS-SERVICE, pipeline message.
// One scan as read back from scan_log. seq is the global scan sequence that
// windowStart/windowEnd are expressed in -- the scan_log primary key, assigned
// by checkout-service's scan-log writer.
public record SequencedScanEvent(long seq, String sku, Instant scannedAt) {}
