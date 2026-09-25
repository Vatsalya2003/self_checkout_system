package com.selfcheckout.self_checkout.dto;

import java.time.Instant;
import java.util.List;

// Response for GET /analytics/popular-items.
//
// windowStart/windowEnd are NOT timestamps -- they're scan sequence numbers
// (scan_log.seq). The window covers scans where seq is in (windowStart, windowEnd].
// Half-open on purpose so back-to-back windows don't double-count the edge scan.
public record PopularItemsResponse(
        int windowSize,        // how many scans the window covers (config: 1000)
        int slideInterval,     // how often we recompute, in scans (config: 500)
        long windowStart,
        long windowEnd,
        Instant computedAt,
        List<PopularItem> items
) {}
