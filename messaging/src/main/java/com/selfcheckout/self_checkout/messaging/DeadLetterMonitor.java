package com.selfcheckout.self_checkout.messaging;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// MESSAGING library (Week 6). A dead-lettered message must never be silent.
//
// Every 10 s, each service logs how many messages sit in the dead-letter
// queues of the queues it consumes (selfcheckout.messaging.watch-dlq), as one
// "dead letters | ..." line -- WARN if any count is above zero. The load-run
// summary greps that line, so a run cannot pass with a dead letter unnoticed.
public class DeadLetterMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final AmqpAdmin admin;
    private final List<String> queues;
    private volatile Thread reporter;

    public DeadLetterMonitor(AmqpAdmin admin, String watched) {
        this.admin = admin;
        this.queues = Arrays.stream(watched.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Topology::dlq).toList();
    }

    @PostConstruct
    void start() {
        if (queues.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10_000);
                    report();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dead-letter-monitor");
        t.setDaemon(true);
        t.start();
        reporter = t;
    }

    /** Message count per watched DLQ; -1 if the broker could not be asked. */
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String q : queues) {
            long n;
            try {
                QueueInformation info = admin.getQueueInfo(q);
                n = info == null ? 0 : info.getMessageCount();
            } catch (RuntimeException e) {
                n = -1;
            }
            out.put(q, n);
        }
        return out;
    }

    void report() {
        Map<String, Long> c = counts();
        StringBuilder line = new StringBuilder("dead letters |");
        boolean any = false;
        for (Map.Entry<String, Long> e : c.entrySet()) {
            line.append(' ').append(e.getKey()).append('=').append(e.getValue() < 0 ? "?" : e.getValue());
            any |= e.getValue() > 0;
        }
        if (any) {
            log.warn("{} -- messages that could not be processed; inspect them with rabbitmqctl", line);
        } else {
            log.info("{}", line);
        }
    }

    @PreDestroy
    void stop() {
        if (reporter != null) {
            reporter.interrupt();
        }
    }
}
