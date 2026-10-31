package com.selfcheckout.self_checkout.messaging;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// MESSAGING library (Week 6). Publishes one persistent message and waits for
// RabbitMQ's publisher confirm, then says which of three things happened.
// The payment saga's safety depends on telling them apart, exactly as Week 5
// told an HTTP "connection refused" apart from a read timeout:
//
//   CONFIRMED      the broker acked: the message is safely in a durable queue.
//   NOT_DELIVERED  certainly NOT in any queue:
//                    - no connection to the broker at all, or
//                    - the broker explicitly refused it (a nack), or
//                    - it was returned as unroutable (no such queue).
//   UNCERTAIN      it may or may not be in the queue:
//                    - the send itself did not return in time (a broker that
//                      is blocking publishers, e.g. on a disk or memory alarm,
//                      simply stops reading -- the write can hang for as long
//                      as the alarm lasts; it may still go through later), or
//                    - no confirm arrived in time, or
//                    - the channel closed before the confirm (Spring then
//                      reports a nack WITH a reason: "Channel closed ...").
//                      A real broker nack has no reason, which is how the two
//                      are told apart.
//
// Messages are persistent (Spring's default delivery mode) and every queue is
// durable, so a CONFIRMED message survives a broker restart.
//
// The send runs on a small pool of its own threads, so the caller's wait --
// send AND confirm together -- never exceeds the timeout, even when the
// broker stops reading. (Found by failure test F: without this, a payment
// hung for 69 s during a disk alarm.)
public class ConfirmedPublisher {

    public enum Outcome { CONFIRMED, NOT_DELIVERED, UNCERTAIN }

    public record Result(Outcome outcome, String detail) {
        public boolean confirmed() {
            return outcome == Outcome.CONFIRMED;
        }
    }

    // Up to 256 sends in flight; idle threads die after a minute. If all 256
    // are stuck on a blocked broker, a new send is refused before anything is
    // written -- which makes it certainly NOT_DELIVERED.
    private static final int MAX_SENDERS = 256;

    private final RabbitTemplate rabbit;
    private final ExecutorService senders;

    public ConfirmedPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
        AtomicInteger n = new AtomicInteger();
        this.senders = new ThreadPoolExecutor(0, MAX_SENDERS, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "amqp-send-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** To a queue, through the default exchange. */
    public Result toQueue(String queue, Object event, Duration confirmTimeout) {
        return publish("", queue, event, confirmTimeout);
    }

    public Result publish(String exchange, String routingKey, Object event, Duration confirmTimeout) {
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        CorrelationData correlation = new CorrelationData();
        Future<?> send;
        try {
            send = senders.submit(() -> rabbit.convertAndSend(exchange, routingKey, event, correlation));
        } catch (RejectedExecutionException e) {
            return new Result(Outcome.NOT_DELIVERED, "too many sends already stuck on the broker; not sent");
        }
        try {
            send.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Not cancelled on purpose: interrupting a half-written frame would
            // break the channel. If it completes later, the message is in.
            return new Result(Outcome.UNCERTAIN, "the send did not complete within " + confirmTimeout.toMillis()
                    + " ms (is the broker blocking publishers?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.UNCERTAIN, "interrupted while sending");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AmqpConnectException) {
                // Could not even get a connection: nothing was sent.
                return new Result(Outcome.NOT_DELIVERED, "broker unreachable: " + rootMessage(e));
            }
            // Failed while writing (AmqpException or anything else): some
            // bytes may have reached the broker.
            return new Result(Outcome.UNCERTAIN, "publish failed mid-write: " + rootMessage(e));
        }
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return new Result(Outcome.UNCERTAIN, "no publisher confirm within " + confirmTimeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.UNCERTAIN, "interrupted while waiting for the confirm");
        } catch (ExecutionException e) {
            return new Result(Outcome.UNCERTAIN, "confirm failed: " + rootMessage(e));
        }
        if (confirm.ack()) {
            if (correlation.getReturned() != null) {
                // mandatory=true: the broker had no queue to put it in.
                return new Result(Outcome.NOT_DELIVERED, "unroutable: " + correlation.getReturned().getReplyText());
            }
            return new Result(Outcome.CONFIRMED, "");
        }
        if (confirm.reason() == null) {
            return new Result(Outcome.NOT_DELIVERED, "broker refused the message (nack)");
        }
        return new Result(Outcome.UNCERTAIN, "channel closed before the confirm: " + confirm.reason());
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
