package com.selfcheckout.self_checkout.analytics.pipeline;

import java.time.Instant;

// ANALYTICS layer, pipeline message.
// What the request thread hands to the pipeline after its scan has COMMITTED.
// No sequence number yet -- the ingest filter assigns that.
public record ScanEvent(String sku, Instant scannedAt) {}
