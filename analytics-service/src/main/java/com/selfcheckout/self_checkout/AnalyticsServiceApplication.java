package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// ANALYTICS-SERVICE entry point. Its own jar, its own process, port 8084, and
// since Week 5 its own database, analytics_db.
//
// Business function: popular items. checkout-service pushes every scan to
// POST /internal/scans in numbered batches; this service stores them in its
// own scan_log, runs them through the Week 3 window -> rank -> sink pipeline,
// writes popular_snapshots, and serves GET /analytics/popular-items. It never
// calls checkout-service; item names come from catalog-service.
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class AnalyticsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalyticsServiceApplication.class, args);
	}
}
