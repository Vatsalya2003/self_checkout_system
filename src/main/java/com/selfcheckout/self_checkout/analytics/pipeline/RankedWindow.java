package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.analytics.result.PopularEntry;

import java.time.Instant;
import java.util.List;

// ANALYTICS layer, pipeline message.
// A window after ranking: the top N, already ordered and numbered, ready to
// be written exactly as-is.
public record RankedWindow(long windowStart, long windowEnd, Instant computedAt,
                           List<PopularEntry> items) {}
