package com.selfcheckout.self_checkout.analytics.pipeline;

import java.time.Instant;

// ANALYTICS layer, pipeline message.
// The same scan once ingest has numbered it. seq is the global scan sequence
// that windowStart/windowEnd are expressed in, and it is also the scan_log
// primary key.
public record SequencedScanEvent(long seq, String sku, Instant scannedAt) {}
