package com.selfcheckout.self_checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * inventory-service's tunables, bound once at startup from application.properties.
 *
 * @param lowStockThreshold a SKU is "low" when its stock is strictly below this
 */
// Week 5: each microservice has its OWN settings record. Until Week 4 there
// was one shared record in a library, because checkout recorded the low-stock
// crossings and inventory reported them, and both had to agree. Now inventory
// does both, so the threshold lives here only -- and no configuration code is
// shared between services at all.
@ConfigurationProperties(prefix = "selfcheckout")
public record SelfCheckoutProperties(int lowStockThreshold) {}
