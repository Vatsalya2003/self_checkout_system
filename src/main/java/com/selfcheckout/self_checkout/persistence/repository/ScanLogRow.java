package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;

// PERSISTENCE layer (database access).
// One scan_log row, ready to insert. Deliberately a persistence-owned type:
// if this were the analytics pipeline's own event record, persistence would
// depend on analytics and the architecture test would fail.
public record ScanLogRow(long seq, String sku, Instant scannedAt) {}
