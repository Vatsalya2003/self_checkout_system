package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.remote.HttpCatalogCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// INVENTORY-SERVICE. Where item names come from: catalog-service's GET /items,
// loaded once and cached (inventory may not read catalog_db). The warm-up
// starts loading at boot, in the background, so a catalog that is still
// starting does not hold this service up.
@Configuration
public class CatalogClientConfig {

    @Bean
    public HttpCatalogCache catalogCache(@Value("${selfcheckout.catalog.url}") String catalogUrl) {
        HttpCatalogCache cache = new HttpCatalogCache(catalogUrl);
        cache.warmUp();
        return cache;
    }
}
