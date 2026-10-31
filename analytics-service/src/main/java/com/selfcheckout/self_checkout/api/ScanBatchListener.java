package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.analytics.ScanIngestService;
import com.selfcheckout.self_checkout.analytics.ScanIngestService.IncomingScan;
import com.selfcheckout.self_checkout.messaging.Events.ScansRecorded;
import com.selfcheckout.self_checkout.messaging.Topology;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// ANALYTICS-SERVICE, API layer (inbound events). Week 6's replacement for
// Week 5's POST /internal/scans: checkout's ScansRecorded batches arrive on
// the analytics.scans queue.
//
// Exactly ONE consumer (concurrency 1): batches are stored one at a time, in
// the order checkout's single writer thread published them, and numbered as
// they are stored (ScanIngestService). The message is acked only when this
// method returns, i.e. after the database commit -- a crash before that and
// RabbitMQ redelivers it, and the batch id makes the redelivery a no-op.
//
// A database error is never a reason to give up on a batch: it is retried
// here, with backoff, until it succeeds (holding the queue in order behind
// it). Only a batch that can never be stored -- malformed, e.g. a scan with no
// SKU -- is rejected to analytics.scans.dlq, and the DeadLetterMonitor counts
// it in every "dead letters |" log line, so it can never go unnoticed.
@Component
public class ScanBatchListener {

    private static final Logger log = LoggerFactory.getLogger(ScanBatchListener.class);

    private final ScanIngestService ingest;

    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong dbRetries = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile long lastSeq;
    private volatile Thread reporter;

    public ScanBatchListener(ScanIngestService ingest) {
        this.ingest = ingest;
    }

    @RabbitListener(queues = Topology.SCANS, concurrency = "1")
    public void onBatch(ScansRecorded batch) {
        long backoff = 100;
        while (true) {
            try {
                var r = ingest.accept(batch.batchId(), batch.scans() == null ? null : batch.scans().stream()
                        .map(s -> s == null ? null : new IncomingScan(s.sku(), s.scannedAt()))
                        .toList());
                batches.incrementAndGet();
                if (r.duplicate()) {
                    duplicates.incrementAndGet();
                } else {
                    stored.addAndGet(r.stored());
                    lastSeq = r.lastSeq();
                }
                return;
            } catch (InvalidRequestException e) {
                rejected.incrementAndGet();
                log.error("scan batch {} can never be stored ({}); sending it to {}",
                        batch.batchId(), e.getMessage(), Topology.dlq(Topology.SCANS));
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            } catch (RuntimeException e) {
                dbRetries.incrementAndGet();
                log.warn("storing scan batch {} failed, retrying in {} ms: {}", batch.batchId(), backoff, e.toString());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted; the batch will be redelivered", ie);
                }
                backoff = Math.min(backoff * 2, 2_000);
            }
        }
    }

    @PostConstruct
    void startReporter() {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10_000);
                    log.info(summary());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "scan-ingest-metrics");
        t.setDaemon(true);
        t.start();
        reporter = t;
    }

    @PreDestroy
    void stopReporter() {
        log.info(summary());
        if (reporter != null) {
            reporter.interrupt();
        }
    }

    public String summary() {
        return String.format("scan ingest | batches=%d stored=%d duplicates=%d rejected=%d dbRetries=%d lastSeq=%d",
                batches.get(), stored.get(), duplicates.get(), rejected.get(), dbRetries.get(), lastSeq);
    }
}
