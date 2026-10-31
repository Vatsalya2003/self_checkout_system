package com.selfcheckout.self_checkout.messaging;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
//                    - no confirm arrived in time, or
//                    - the channel closed before the confirm (Spring then
//                      reports a nack WITH a reason: "Channel closed ...").
//                      A real broker nack has no reason, which is how the two
//                      are told apart.
//
// Messages are persistent (Spring's default delivery mode) and every queue is
// durable, so a CONFIRMED message survives a broker restart.
public class ConfirmedPublisher {

    public enum Outcome { CONFIRMED, NOT_DELIVERED, UNCERTAIN }

    public record Result(Outcome outcome, String detail) {
        public boolean confirmed() {
            return outcome == Outcome.CONFIRMED;
        }
    }

    private final RabbitTemplate rabbit;

    public ConfirmedPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /** To a queue, through the default exchange. */
    public Result toQueue(String queue, Object event, Duration confirmTimeout) {
        return publish("", queue, event, confirmTimeout);
    }

    public Result publish(String exchange, String routingKey, Object event, Duration confirmTimeout) {
        CorrelationData correlation = new CorrelationData();
        try {
            rabbit.convertAndSend(exchange, routingKey, event, correlation);
        } catch (AmqpConnectException e) {
            // Could not even get a connection: nothing was sent.
            return new Result(Outcome.NOT_DELIVERED, "broker unreachable: " + rootMessage(e));
        } catch (AmqpException e) {
            // Failed while writing: some bytes may have reached the broker.
            return new Result(Outcome.UNCERTAIN, "publish failed mid-write: " + rootMessage(e));
        }
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
