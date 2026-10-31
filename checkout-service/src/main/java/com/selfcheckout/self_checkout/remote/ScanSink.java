package com.selfcheckout.self_checkout.remote;

import java.util.List;

// CHECKOUT-SERVICE, remote layer. Where scans go: analytics-service, through
// the broker. An interface so the publisher (scanlog layer) is unit-tested
// with a fake and never sees RabbitMQ.
public interface ScanSink {

    /**
     * Publish one batch, in scan order. Returns only once the broker has
     * confirmed it is in the durable analytics.scans queue. Any exception means
     * "not known to be delivered -- send the same batch again, with the same
     * batchId" (analytics skips a batch id it has already stored).
     */
    void push(String batchId, List<RecordedScan> scans);
}
