package com.selfcheckout.self_checkout.remote;

import com.selfcheckout.self_checkout.messaging.ConfirmedPublisher;
import com.selfcheckout.self_checkout.messaging.Events.ScansRecorded;
import com.selfcheckout.self_checkout.messaging.Topology;

import java.time.Duration;
import java.util.List;

// CHECKOUT-SERVICE, remote layer: publishes scan batches to analytics as
// ScansRecorded events on the analytics.scans queue.
//
// Week 6's replacement for Week 5's HttpScanSink (POST /internal/scans).
// Called only by the publisher's single writer thread, which publishes batch
// N+1 only after batch N has been confirmed -- so batches enter the queue in
// order, and analytics' single consumer stores them in that order.
public class BrokerScanSink implements ScanSink {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final ConfirmedPublisher publisher;

    public BrokerScanSink(ConfirmedPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void push(String batchId, List<RecordedScan> scans) {
        ScansRecorded event = new ScansRecorded(batchId, scans.stream()
                .map(s -> new ScansRecorded.Scan(s.sku(), s.scannedAt()))
                .toList());
        ConfirmedPublisher.Result r = publisher.toQueue(Topology.SCANS, event, CONFIRM_TIMEOUT);
        if (!r.confirmed()) {
            // NOT_DELIVERED or UNCERTAIN: either way the writer re-sends the
            // same batch id, and analytics ignores it if it already has it.
            throw new IllegalStateException("scan batch " + batchId + " not confirmed: " + r.detail());
        }
    }
}
