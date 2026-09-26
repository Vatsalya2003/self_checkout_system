package com.selfcheckout.self_checkout.analytics.result;

import java.time.Instant;
import java.util.List;

// ANALYTICS layer, result record. One computed hopping window.
// windowStart/windowEnd are scan sequence numbers, not timestamps.
public record PopularWindowView(
        int windowSize,
        int slideInterval,
        long windowStart,
        long windowEnd,
        Instant computedAt,
        List<PopularEntry> items
) {}
