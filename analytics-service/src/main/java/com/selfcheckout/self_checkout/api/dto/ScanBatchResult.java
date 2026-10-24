package com.selfcheckout.self_checkout.api.dto;

// ANALYTICS-SERVICE, API layer. Answer to POST /internal/scans and
// GET /internal/scans/max-seq. Sent only after the batch has committed.
public record ScanBatchResult(int inserted, int duplicates, long maxSeq) {}
