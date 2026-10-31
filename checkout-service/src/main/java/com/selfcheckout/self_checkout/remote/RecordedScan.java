package com.selfcheckout.self_checkout.remote;

import java.time.Instant;

// CHECKOUT-SERVICE, remote layer. One scan as it is published to analytics in
// a ScansRecorded batch. Week 6: no seq any more -- analytics numbers the
// scans as it stores them, so checkout no longer has to know where the
// numbering is (Week 5's ScanLogRow carried the seq checkout had assigned).
public record RecordedScan(String sku, Instant scannedAt) {}
