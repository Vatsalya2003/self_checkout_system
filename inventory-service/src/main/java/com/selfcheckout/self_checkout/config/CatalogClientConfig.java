package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.remote.BrokerCatalogCache;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// INVENTORY-SERVICE. Where item names come from: catalog-service's catalog
// snapshot, received as an event and cached (inventory may not read
// catalog_db). Week 6: asked for with CatalogRequested and delivered on the
// catalog.snapshots fanout, instead of Week 5's GET /items. The warm-up asks
// at boot, in the background, so a catalog that is still starting does not
// hold this service up.
@Configuration
public class CatalogClientConfig {

    @Bean
    public BrokerCatalogCache catalogCache(RabbitTemplate rabbit) {
        BrokerCatalogCache cache = new BrokerCatalogCache(rabbit, "inventory-service");
        cache.warmUp();
        return cache;
    }
}
