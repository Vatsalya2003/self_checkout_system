package com.selfcheckout.self_checkout.analytics.pipeline;

import java.util.Map;

// ANALYTICS layer, pipeline message.
// One completed window: how many times each SKU was scanned in
// (windowStart, windowEnd].
//
// counts is an immutable copy taken at emit time. The window filter keeps
// mutating its own live map afterwards, so handing the live map downstream
// would let two threads read and write the same structure.
public record WindowCounts(long windowStart, long windowEnd, Map<String, Long> counts) {}
