package com.selfcheckout.self_checkout.repository;

/** Query projection: one SKU's scan count inside a window. */
// A "projection" just means: a small record to receive query results, instead
// of loading whole entities we don't need. Built directly by the JPQL
// "select new ...ScanCount(...)" in ScanLogRepository.
public record ScanCount(String sku, String name, long scanCount) {}
