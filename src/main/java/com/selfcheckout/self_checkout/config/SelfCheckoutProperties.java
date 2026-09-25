package com.selfcheckout.self_checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables from application.properties, bound once at startup.
 *
 * @param lowStockThreshold    a SKU is "low" when its stock is strictly below this
 * @param popularWindowSize    how many of the most recent scans the popular-items window covers
 * @param popularSlideInterval how often (in scans) that window is recomputed
 */
// A record + @ConfigurationProperties means Spring reads the "selfcheckout.*"
// keys at boot and hands services one immutable, typed object -- much nicer
// than scattering @Value("${...}") strings around.
// Kebab-case in the file maps to camelCase here:
//   selfcheckout.low-stock-threshold -> lowStockThreshold
// Registered via @EnableConfigurationProperties on SelfCheckoutApplication.
@ConfigurationProperties(prefix = "selfcheckout")
public record SelfCheckoutProperties(
        int lowStockThreshold,
        int popularWindowSize,
        int popularSlideInterval
) {}
