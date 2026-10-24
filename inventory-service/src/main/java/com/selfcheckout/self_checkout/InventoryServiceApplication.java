package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// INVENTORY-SERVICE entry point. Its own jar, its own process, port 8083, and
// since Week 5 its own database, inventory_db.
//
// Business function: stock. It owns every unit on the shelf and every
// low-stock alert, and it is the only service that changes them:
//   GET  /inventory/low-stock        public, through the gateway
//   POST /internal/stock/commit      internal: the stock half of a payment,
//                                    called by checkout-service (the saga's
//                                    orchestrator), never through the gateway
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class InventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InventoryServiceApplication.class, args);
	}
}
