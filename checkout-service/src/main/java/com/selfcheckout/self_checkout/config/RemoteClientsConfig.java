package com.selfcheckout.self_checkout.config;

import com.selfcheckout.self_checkout.remote.HttpCatalogCache;
import com.selfcheckout.self_checkout.remote.HttpInventoryClient;
import com.selfcheckout.self_checkout.remote.HttpScanSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// CHECKOUT-SERVICE. The three other services checkout talks to, and where
// they are. Each call goes directly to the service's own port -- never
// through the gateway, which only carries client traffic and refuses
// /internal/** anyway.
//
//   catalog-service    GET  /items                   once, cached (prices, names)
//   inventory-service  POST /internal/stock/commit   the payment saga's pivot
//   analytics-service  POST /internal/scans          every scan, in batches
@Configuration
public class RemoteClientsConfig {

    @Bean
    public HttpCatalogCache catalogCache(@Value("${selfcheckout.catalog.url}") String url) {
        HttpCatalogCache cache = new HttpCatalogCache(url);
        cache.warmUp();          // in the background: a slow catalog does not block startup
        return cache;
    }

    @Bean
    public HttpInventoryClient inventoryClient(
            @Value("${selfcheckout.inventory.url}") String url,
            @Value("${selfcheckout.inventory.read-timeout-ms:2500}") long readTimeoutMs) {
        return new HttpInventoryClient(url, Duration.ofMillis(readTimeoutMs));
    }

    @Bean
    public HttpScanSink scanSink(@Value("${selfcheckout.analytics.url}") String url) {
        return new HttpScanSink(url);
    }
}
