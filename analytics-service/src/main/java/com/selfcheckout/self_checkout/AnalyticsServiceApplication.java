package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// ANALYTICS-SERVICE entry point. Its own jar, its own process, port 8084.
//
// Business function: popular items. It reads the scans checkout-service
// appends to scan_log, runs them through the Week 3 window -> rank -> sink
// pipeline, writes popular_snapshots (the only table it writes), and serves
// GET /analytics/popular-items. It never calls checkout-service, and
// checkout-service never calls it: scan_log in the shared database is the
// whole hand-off.
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class AnalyticsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalyticsServiceApplication.class, args);
	}
}
