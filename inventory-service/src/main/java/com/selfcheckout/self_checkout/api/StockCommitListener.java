package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.inventory.StockCommitService;
import com.selfcheckout.self_checkout.inventory.StockCommitService.RequestedLine;
import com.selfcheckout.self_checkout.inventory.exception.CommitConflictException;
import com.selfcheckout.self_checkout.inventory.result.StockCommitResult;
import com.selfcheckout.self_checkout.messaging.ConfirmedPublisher;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitRejected;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitRequested;
import com.selfcheckout.self_checkout.messaging.Events.StockCommitted;
import com.selfcheckout.self_checkout.messaging.Topology;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// INVENTORY-SERVICE, API layer (inbound events). Week 6's replacement for
// Week 5's POST /internal/stock/commit: checkout's StockCommitRequested
// arrives on the inventory.stock-commit queue, and the answer goes back as a
// message on checkout.stock-replies.
//
// For each request, in this order:
//   1. run the SAME idempotent local transaction as Week 5
//      (StockCommitService: claim the id, lock in SKU order, clamp, decrement,
//      record the lines and low-stock alerts, commit);
//   2. publish the reply (StockCommitted, or StockCommitRejected if the
//      request itself is invalid) and wait for the broker's confirm;
//   3. return -- and only then does Spring ack the request message.
//
// Why acking last makes every crash safe: if this process dies anywhere
// before step 3, the broker still holds the request and redelivers it. The
// commit is idempotent on transactionId, so the redelivery takes no more
// stock and just answers from the stored result. That is also why no
// "transactional outbox" is needed: the reply can always be rebuilt from what
// inventory_db already holds.
//
// Many consumers run at once (competing consumers, see application.properties);
// order between payments does not matter, and the row locks keep them correct
// exactly as they did for concurrent HTTP requests.
@Component
public class StockCommitListener {

    private static final Logger log = LoggerFactory.getLogger(StockCommitListener.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final StockCommitService commits;
    private final ConfirmedPublisher publisher;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong replayed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong dbRetries = new AtomicLong();
    private final AtomicLong replyRetries = new AtomicLong();
    private volatile Thread reporter;

    public StockCommitListener(StockCommitService commits, ConfirmedPublisher publisher) {
        this.commits = commits;
        this.publisher = publisher;
    }

    @RabbitListener(queues = Topology.STOCK_COMMIT_REQUESTS,
                    concurrency = "${selfcheckout.stock-commit.consumers:32}")
    public void onRequest(StockCommitRequested request) {
        requests.incrementAndGet();
        Object reply = commitWithRetry(request);
        ConfirmedPublisher.Result r = publisher.toQueue(Topology.STOCK_COMMIT_REPLIES, reply, CONFIRM_TIMEOUT);
        if (!r.confirmed()) {
            // Not acked -> the broker redelivers the request, which is
            // answered again from the stored commit. Nothing is taken twice.
            replyRetries.incrementAndGet();
            throw new IllegalStateException("reply for " + request.transactionId() + " not confirmed: " + r.detail());
        }
    }

    /**
     * The commit itself. An invalid request is answered with a rejection (no
     * stock moved, the transaction rolled back). Anything else -- the database
     * restarting, a lost connection -- is treated as temporary and retried
     * here with backoff, holding this one message: a payment request is never
     * dead-lettered for a transient failure.
     */
    private Object commitWithRetry(StockCommitRequested request) {
        long backoff = 50;
        while (true) {
            try {
                if (request.lines() == null) {
                    throw new InvalidRequestException("transactionId and lines are required");
                }
                StockCommitResult res = commits.commit(request.transactionId(), request.lines().stream()
                        .map(l -> l == null ? null : new RequestedLine(l.sku(), l.quantity()))
                        .toList());
                (res.replayed() ? replayed : committed).incrementAndGet();
                List<StockCommitted.Line> lines = res.lines().stream()
                        .map(l -> new StockCommitted.Line(l.sku(), l.quantity(), l.applied()))
                        .toList();
                return new StockCommitted(res.transactionId(), res.replayed(), lines);
            } catch (InvalidRequestException | ItemNotFoundException | CommitConflictException e) {
                rejected.incrementAndGet();
                return new StockCommitRejected(request.transactionId(), e.getMessage());
            } catch (RuntimeException e) {
                dbRetries.incrementAndGet();
                log.warn("stock commit for {} failed, retrying in {} ms: {}", request.transactionId(), backoff, e.toString());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted; the request will be redelivered", ie);
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
        }, "stock-commit-metrics");
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
        return String.format("stock-commit listener | requests=%d committed=%d replayed=%d rejected=%d "
                        + "dbRetries=%d replyRetries=%d",
                requests.get(), committed.get(), replayed.get(), rejected.get(), dbRetries.get(), replyRetries.get());
    }
}
