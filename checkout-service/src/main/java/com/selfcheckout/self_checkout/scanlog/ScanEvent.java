package com.selfcheckout.self_checkout.scanlog;

import java.time.Instant;

// CHECKOUT-SERVICE, scan-log layer.
// What the request thread hands to the scan-log writer once a scan has
// succeeded. No sequence number yet: the single writer thread assigns those,
// in the order it writes them, which is what keeps the sequence gapless.
public record ScanEvent(String sku, Instant scannedAt) {}
