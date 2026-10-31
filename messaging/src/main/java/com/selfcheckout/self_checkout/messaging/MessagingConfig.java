package com.selfcheckout.self_checkout.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

// MESSAGING library (Week 6). Picked up by every service's component scan
// (same base package), so all four declare the same broker objects the same
// way. Declaring is idempotent: whichever service starts first creates them,
// the others just confirm they exist with the same settings.
@Configuration
public class MessagingConfig {

    /**
     * Every queue, exchange and dead-letter route. Durable, so they survive a
     * broker restart; classic queues, because a single broker gains nothing
     * from replicated (quorum) queues.
     */
    @Bean
    public Declarables brokerTopology() {
        List<Declarable> all = new ArrayList<>();
        DirectExchange deadLetters = new DirectExchange(Topology.DEAD_LETTERS, true, false);
        all.add(deadLetters);
        for (String name : Topology.QUEUES) {
            Queue q = QueueBuilder.durable(name)
                    .deadLetterExchange(Topology.DEAD_LETTERS)
                    .deadLetterRoutingKey(Topology.dlq(name))
                    .build();
            Queue dlq = QueueBuilder.durable(Topology.dlq(name)).build();
            Binding toDlq = BindingBuilder.bind(dlq).to(deadLetters).with(Topology.dlq(name));
            all.add(q);
            all.add(dlq);
            all.add(toDlq);
        }
        all.add(new FanoutExchange(Topology.CATALOG_SNAPSHOTS, true, false));
        return new Declarables(all);
    }

    /**
     * JSON on the wire (Jackson 3). Only classes from this package may be
     * created from a message, so a stray header cannot make us instantiate
     * anything else.
     */
    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter(Events.class.getPackageName());
    }

    @Bean
    public ConfirmedPublisher confirmedPublisher(RabbitTemplate rabbit) {
        return new ConfirmedPublisher(rabbit);
    }

    @Bean
    public DeadLetterMonitor deadLetterMonitor(AmqpAdmin admin,
                                               @Value("${selfcheckout.messaging.watch-dlq:}") String watched) {
        return new DeadLetterMonitor(admin, watched);
    }
}
