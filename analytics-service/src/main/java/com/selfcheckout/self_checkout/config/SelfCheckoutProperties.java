package com.selfcheckout.self_checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * analytics-service's tunables, bound once at startup from application.properties.
 *
 * @param popularWindowSize    how many of the most recent scans the popular-items window covers
 * @param popularSlideInterval how often (in scans) that window is recomputed
 */
// Week 5: each microservice has its OWN settings record (no shared
// configuration library any more); analytics only needs these two.
@ConfigurationProperties(prefix = "selfcheckout")
public record SelfCheckoutProperties(int popularWindowSize, int popularSlideInterval) {}
