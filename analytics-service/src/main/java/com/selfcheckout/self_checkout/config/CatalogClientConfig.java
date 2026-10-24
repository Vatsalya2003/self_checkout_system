package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.remote.HttpCatalogCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ANALYTICS-SERVICE. Where item names for the ranking come from:
// catalog-service's GET /items, loaded once and cached (analytics may not read
// catalog_db). Warm-up starts in the background at boot.
@Configuration
public class CatalogClientConfig {

    @Bean
    public HttpCatalogCache catalogCache(@Value("${selfcheckout.catalog.url}") String catalogUrl) {
        HttpCatalogCache cache = new HttpCatalogCache(catalogUrl);
        cache.warmUp();
        return cache;
    }
}
