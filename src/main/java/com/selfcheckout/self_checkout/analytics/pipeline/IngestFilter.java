package com.selfcheckout.self_checkout.analytics.pipeline;

/**
 * FILTER 1 of 5 -- ingest.
 *
 * Numbers each scan and tees it to the two downstream pipes.
 *
 * The sequence it hands out IS the global scan sequence: it becomes the
 * scan_log primary key and the windowStart/windowEnd the API reports. Because
 * only committed scans ever reach this filter, and this filter is
 * single-threaded, the numbers are gapless and strictly increasing.
 *
 * Why a filter and not just an AtomicLong in the request path: the request
 * thread then has exactly ONE handover to make. Teeing from the request
 * thread would give it two places it could be made to wait.
 */
public class IngestFilter {

    private long sequence = 0;            // plain long: one thread owns it

    /** Number this event. */
    public SequencedScanEvent assign(ScanEvent event) {
        return new SequencedScanEvent(++sequence, event.sku(), event.scannedAt());
    }

    /** How many events have been numbered so far. */
    public long assigned() { return sequence; }
}
