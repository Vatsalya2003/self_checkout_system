package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.catalog.CatalogService;
import com.selfcheckout.self_checkout.messaging.Events.CatalogPublished;
import com.selfcheckout.self_checkout.messaging.Events.CatalogRequested;
import com.selfcheckout.self_checkout.messaging.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// CATALOG-SERVICE, API layer (inbound events). Week 6's replacement for the
// other services calling GET /items on this service directly.
//
// The catalog is broadcast as one CatalogPublished message on the
// catalog.snapshots fanout exchange: once when this service starts, and again
// whenever a CatalogRequested arrives (a service that started after us, or
// one that missed the broadcast). RabbitMQ does not keep a copy for
// latecomers, which is why the request exists. Publishing the same snapshot
// twice is harmless: a consumer keeps the first one it gets.
//
// GET /items for the load client is unchanged (ItemController): that is
// client traffic through the gateway, not cross-service communication.
@Component
public class CatalogEventsListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventsListener.class);

    private final CatalogService catalog;
    private final RabbitTemplate rabbit;

    public CatalogEventsListener(CatalogService catalog, RabbitTemplate rabbit) {
        this.catalog = catalog;
        this.rabbit = rabbit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void publishOnStartup() {
        try {
            publish();
        } catch (RuntimeException e) {
            // Not fatal: anyone who still needs the catalog will ask for it.
            log.warn("could not broadcast the catalog at startup: {}", e.toString());
        }
    }

    @RabbitListener(queues = Topology.CATALOG_REQUESTS)
    public void onRequest(CatalogRequested request) {
        publish();
    }

    private void publish() {
        var items = catalog.listItems().stream()
                .map(i -> new CatalogPublished.Item(i.sku(), i.name(), i.price()))
                .toList();
        rabbit.convertAndSend(Topology.CATALOG_SNAPSHOTS, "", new CatalogPublished(items));
        log.info("catalog broadcast on {}: {} items", Topology.CATALOG_SNAPSHOTS, items.size());
    }
}
