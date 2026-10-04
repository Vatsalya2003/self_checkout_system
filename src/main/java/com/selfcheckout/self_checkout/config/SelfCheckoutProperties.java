package com.selfcheckout.self_checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables from application.properties, bound once at startup.
 *
 * @param lowStockThreshold    a SKU is "low" when its stock is strictly below this
 * @param popularWindowSize    how many of the most recent scans the popular-items window covers
 * @param popularSlideInterval how often (in scans) that window is recomputed
 * @param analytics            analytics-specific switches
 */
// A record + @ConfigurationProperties means Spring reads the "selfcheckout.*"
// keys at boot and hands services one immutable, typed object.
// Kebab-case in the file maps to camelCase here:
//   selfcheckout.low-stock-threshold -> lowStockThreshold
//   selfcheckout.analytics.persist-raw-scans -> analytics().persistRawScans()
@ConfigurationProperties(prefix = "selfcheckout")
public record SelfCheckoutProperties(
        int lowStockThreshold,
        int popularWindowSize,
        int popularSlideInterval,
        Analytics analytics
) {

    /**
     * @param persistRawScans whether every individual scan is written to scan_log.
     *                        Default false: the assignment only requires the
     *                        top-N windows to reach the database, and the
     *                        windows are computed in memory from the event
     *                        stream, not from those rows.
     */
    public record Analytics(boolean persistRawScans) {}

    // If the whole "selfcheckout.analytics" block is absent, Spring binds null.
    // Default it here so the rest of the code never has to null-check.
    public SelfCheckoutProperties {
        if (analytics == null) {
            analytics = new Analytics(false);
        }
    }
}
