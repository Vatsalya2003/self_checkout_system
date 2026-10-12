package com.selfcheckout.self_checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables from each service's application.properties, bound once at startup.
 *
 * @param lowStockThreshold    a SKU is "low" when its stock is strictly below this
 *                             (checkout-service records crossings, inventory-service reports them)
 * @param popularWindowSize    how many of the most recent scans the popular-items window covers
 *                             (analytics-service)
 * @param popularSlideInterval how often (in scans) that window is recomputed (analytics-service)
 */
// SHARED-DATA library. A record + @ConfigurationProperties means Spring reads
// the "selfcheckout.*" keys at boot and hands services one immutable, typed
// object. Kebab-case in the file maps to camelCase here:
//   selfcheckout.low-stock-threshold -> lowStockThreshold
//
// Shared because two services must agree on the same threshold. Each service
// only sets the keys it uses; an unset int binds to 0.
//
// Week 4: the old selfcheckout.analytics.persist-raw-scans switch is gone.
// scan_log is now ALWAYS written, because it is the hand-off from
// checkout-service to analytics-service -- turning it off would stop analytics.
@ConfigurationProperties(prefix = "selfcheckout")
public record SelfCheckoutProperties(
        int lowStockThreshold,
        int popularWindowSize,
        int popularSlideInterval
) {}
