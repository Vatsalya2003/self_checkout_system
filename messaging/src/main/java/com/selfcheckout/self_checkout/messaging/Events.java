package com.selfcheckout.self_checkout.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// MESSAGING library (Week 6). The messages the services exchange, as plain
// records. They travel as JSON; the publisher's record class name rides along
// in a header, and the consumer only accepts classes from this package.
//
// Each one replaces a Week 5 HTTP call:
//
//   CatalogRequested / CatalogPublished       GET /items at startup
//   StockCommitRequested                      POST /internal/stock/commit (the request)
//   StockCommitted / StockCommitRejected      ... and its response
//   ScansRecorded                             POST /internal/scans (and GET /internal/scans/max-seq is gone)
public final class Events {

    private Events() {}

    /** "Please publish the catalog." Sent by a service that has no copy yet. */
    public record CatalogRequested(String requestedBy) {}

    /** The whole catalog, SKU order. Broadcast on catalog.snapshots. */
    public record CatalogPublished(List<Item> items) {
        public record Item(String sku, String name, BigDecimal price) {}
    }

    /**
     * Take the stock for this payment: the saga's pivot. Idempotent on
     * transactionId -- sending it again never takes the stock twice.
     */
    public record StockCommitRequested(String transactionId, List<Line> lines) {
        public record Line(String sku, int quantity) {}
    }

    /** Inventory committed (or had already committed) the stock. applied = units actually taken. */
    public record StockCommitted(String transactionId, boolean replayed, List<Line> lines) {
        public record Line(String sku, int quantity, int applied) {}
    }

    /** Inventory refused the request and took nothing (e.g. an unknown SKU). */
    public record StockCommitRejected(String transactionId, String reason) {}

    /**
     * A batch of scans, in scan order. No sequence numbers: analytics numbers
     * them as it stores them. batchId makes a redelivered batch harmless.
     */
    public record ScansRecorded(String batchId, List<Scan> scans) {
        public record Scan(String sku, Instant scannedAt) {}
    }
}
