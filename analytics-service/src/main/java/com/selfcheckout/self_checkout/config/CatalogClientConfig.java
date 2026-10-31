package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.remote.BrokerCatalogCache;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ANALYTICS-SERVICE. Where item names for the ranking come from:
// catalog-service's catalog snapshot, received as an event and cached
// (analytics may not read catalog_db). Week 6: asked for with
// CatalogRequested and delivered on the catalog.snapshots fanout, instead of
// Week 5's GET /items. Warm-up starts in the background at boot.
@Configuration
public class CatalogClientConfig {

    @Bean
    public BrokerCatalogCache catalogCache(RabbitTemplate rabbit) {
        BrokerCatalogCache cache = new BrokerCatalogCache(rabbit, "analytics-service");
        cache.warmUp();
        return cache;
    }
}
