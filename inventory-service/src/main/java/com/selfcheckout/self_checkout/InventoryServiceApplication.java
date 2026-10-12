package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// INVENTORY-SERVICE entry point. Its own jar, its own process, port 8083.
//
// Business function: stock reporting. One endpoint, GET /inventory/low-stock.
// It only READS: current stock from items and recorded crossings from
// low_stock_alerts. Both are written by checkout-service, in the payment's own
// database transaction -- the shared database is the only thing the two
// services have in common.
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class InventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InventoryServiceApplication.class, args);
	}
}
