package com.selfcheckout.self_checkout.persistence.repository;

import java.time.Instant;

// PERSISTENCE layer (database access).
// One computed popular-items window, ready to insert.
public record SnapshotRow(long windowStart, long windowEnd, Instant computedAt, String itemsJson) {}
