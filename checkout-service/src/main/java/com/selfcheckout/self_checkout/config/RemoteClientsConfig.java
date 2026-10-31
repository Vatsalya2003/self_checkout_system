package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.messaging.ConfirmedPublisher;
import com.selfcheckout.self_checkout.remote.BrokerCatalogCache;
import com.selfcheckout.self_checkout.remote.BrokerInventoryClient;
import com.selfcheckout.self_checkout.remote.BrokerScanSink;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// CHECKOUT-SERVICE. The three other services checkout talks to -- since Week 6
// only through RabbitMQ, never over HTTP:
//
//   catalog-service    CatalogRequested -> catalog.requests,
//                      CatalogPublished <- catalog.snapshots    once, cached (prices, names)
//   inventory-service  StockCommitRequested -> inventory.stock-commit,
//                      StockCommitted / Rejected <- checkout.stock-replies   the saga's pivot
//   analytics-service  ScansRecorded -> analytics.scans         every scan, in batches
@Configuration
public class RemoteClientsConfig {

    @Bean
    public BrokerCatalogCache catalogCache(RabbitTemplate rabbit) {
        BrokerCatalogCache cache = new BrokerCatalogCache(rabbit, "checkout-service");
        cache.warmUp();          // in the background: a slow catalog does not block startup
        return cache;
    }

    @Bean
    public BrokerInventoryClient inventoryClient(
            ConfirmedPublisher publisher,
            @Value("${selfcheckout.inventory.confirm-timeout-ms:1000}") long confirmTimeoutMs,
            @Value("${selfcheckout.inventory.reply-timeout-ms:2500}") long replyTimeoutMs) {
        return new BrokerInventoryClient(publisher, Duration.ofMillis(confirmTimeoutMs), Duration.ofMillis(replyTimeoutMs));
    }

    @Bean
    public BrokerScanSink scanSink(ConfirmedPublisher publisher) {
        return new BrokerScanSink(publisher);
    }
}
