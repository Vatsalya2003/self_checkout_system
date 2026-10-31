package com.selfcheckout.self_checkout.scanlog;

import java.time.Instant;

// CHECKOUT-SERVICE, scan-log layer.
// What the request thread hands to the scan-log writer once a scan has
// succeeded. No sequence number: since Week 6 analytics numbers the scans as
// it stores them, in the order the single writer thread published them.
public record ScanEvent(String sku, Instant scannedAt) {}
