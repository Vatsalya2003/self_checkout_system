package com.selfcheckout.self_checkout.analytics.result;

// ANALYTICS layer, result record. One ranked item in a window.
// rank is 1-based.
public record PopularEntry(String sku, String name, long scanCount, int rank) {}
