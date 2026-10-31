package com.selfcheckout.self_checkout.remote;

import com.selfcheckout.self_checkout.messaging.ConfirmedPublisher;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitRejected;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitRequested;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitted;
import com.selfcheckout.self_checkout.messaging.Topology;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

// CHECKOUT-SERVICE, remote layer: the payment saga's pivot over the broker.
//
// Week 6's replacement for Week 5's HttpInventoryClient (POST
// /internal/stock/commit). One attempt is:
//
//   1. register a future under the transaction id (the correlation id)
//   2. publish StockCommitRequested to inventory.stock-commit (persistent)
//      and wait for the broker's publisher confirm
//   3. wait for inventory's answer, which arrives on checkout.stock-replies
//      and is handed to the future by onReply() below
//
// Sorting what happened into the saga's three outcomes (StockCommitOutcome)
// is the important part, exactly as it was for HTTP:
//
//   no broker connection / broker nack / unroutable   -> NotDelivered
//       the request is certainly not in the queue, so inventory can never act
//       on it: safe to reopen the basket.
//   confirm timed out / channel closed before confirm -> Uncertain
//   confirmed, but no reply in time                    -> Uncertain
//       the request may be (or certainly is) in the durable queue, and
//       inventory WILL process it when it gets to it -- even if it is down
//       right now. Reopening the basket could sell the same units twice, so
//       the saga keeps it closed and the resolver finishes the payment.
//   StockCommitted                                     -> Committed
//   StockCommitRejected (an invalid request)           -> NotDelivered
//       inventory rolled back and took nothing.
//
// A reply that arrives when nobody is waiting any more (the request gave up,
// or checkout restarted) is counted and dropped: the resolver re-sends the
// same request, and inventory answers it again from its stored result.
@RabbitListener(queues = Topology.STOCK_COMMIT_REPLIES, concurrency = "${selfcheckout.stock-replies.consumers:4}")
public class BrokerInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(BrokerInventoryClient.class);

    private final ConfirmedPublisher publisher;
    private final Duration confirmTimeout;
    private final Duration replyTimeout;
    private final Map<String, CompletableFuture<Object>> waiting = new ConcurrentHashMap<>();

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong notDelivered = new AtomicLong();
    private final AtomicLong unconfirmed = new AtomicLong();
    private final AtomicLong noReply = new AtomicLong();
    private final AtomicLong replies = new AtomicLong();
    private final AtomicLong strayReplies = new AtomicLong();
    private volatile Thread reporter;

    public BrokerInventoryClient(ConfirmedPublisher publisher, Duration confirmTimeout, Duration replyTimeout) {
        this.publisher = publisher;
        this.confirmTimeout = confirmTimeout;
        this.replyTimeout = replyTimeout;
    }

    @Override
    public StockCommitOutcome commit(String transactionId, Map<String, Integer> quantityBySku) {
        StockCommitRequested request = new StockCommitRequested(transactionId, quantityBySku.entrySet().stream()
                .map(e -> new StockCommitRequested.Line(e.getKey(), e.getValue())).toList());
        CompletableFuture<Object> reply = new CompletableFuture<>();
        // Registered BEFORE publishing, so even an instant reply finds it. A
        // retry of the same payment replaces it; a reply to the earlier
        // attempt then answers the retry, which is fine -- same answer.
        waiting.put(transactionId, reply);
        try {
            published.incrementAndGet();
            ConfirmedPublisher.Result sent = publisher.toQueue(Topology.STOCK_COMMIT_REQUESTS, request, confirmTimeout);
            switch (sent.outcome()) {
                case NOT_DELIVERED -> {
                    notDelivered.incrementAndGet();
                    return new StockCommitOutcome.NotDelivered(sent.detail());
                }
                case UNCERTAIN -> {
                    unconfirmed.incrementAndGet();
                    return new StockCommitOutcome.Uncertain(sent.detail());
                }
                case CONFIRMED -> { /* in the queue: now wait for inventory */ }
            }
            Object answer;
            try {
                answer = reply.get(replyTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                noReply.incrementAndGet();
                return new StockCommitOutcome.Uncertain("no reply from inventory within " + replyTimeout.toMillis() + " ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StockCommitOutcome.Uncertain("interrupted while waiting for inventory");
            } catch (Exception e) {
                return new StockCommitOutcome.Uncertain("waiting for inventory failed: " + e);
            }
            if (answer instanceof StockCommitted c) {
                Map<String, Integer> applied = new HashMap<>();
                c.lines().forEach(l -> applied.put(l.sku(), l.applied()));
                return new StockCommitOutcome.Committed(applied, c.replayed());
            }
            StockCommitRejected r = (StockCommitRejected) answer;
            return new StockCommitOutcome.NotDelivered("inventory rejected the request: " + r.reason());
        } finally {
            waiting.remove(transactionId, reply);
        }
    }

    // ---- replies from inventory (checkout.stock-replies) -----------------

    @RabbitHandler
    public void onCommitted(StockCommitted reply) {
        deliver(reply.transactionId(), reply);
    }

    @RabbitHandler
    public void onRejected(StockCommitRejected reply) {
        deliver(reply.transactionId(), reply);
    }

    private void deliver(String transactionId, Object reply) {
        replies.incrementAndGet();
        CompletableFuture<Object> f = waiting.get(transactionId);
        if (f == null || !f.complete(reply)) {
            strayReplies.incrementAndGet();          // nobody waiting: the resolver will ask again
        }
    }

    // ---- metrics ---------------------------------------------------------

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
        }, "stock-client-metrics");
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
        return String.format("stock-commit client | published=%d notDelivered=%d unconfirmed=%d noReply=%d "
                        + "| replies=%d stray=%d waiting=%d",
                published.get(), notDelivered.get(), unconfirmed.get(), noReply.get(),
                replies.get(), strayReplies.get(), waiting.size());
    }
}
