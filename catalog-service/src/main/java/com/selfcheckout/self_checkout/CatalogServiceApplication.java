package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// CATALOG-SERVICE entry point. Its own jar, its own process, port 8081.
//
// Business function: the product catalog. One endpoint, GET /items, answered
// from the shared catalog cache (shared-data) -- the service reads the items
// table and never writes anything.
//
// Starting it does NOT touch the database schema any more: db/reset.sh is the
// one thing allowed to run schema.sql and data.sql, and every service runs
// with spring.sql.init.mode=never.
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class CatalogServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CatalogServiceApplication.class, args);
	}
}
