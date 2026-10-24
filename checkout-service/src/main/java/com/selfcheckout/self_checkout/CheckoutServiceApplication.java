package com.selfcheckout.self_checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// CHECKOUT-SERVICE entry point. Its own jar, its own process, port 8082, and
// since Week 5 its own database, checkout_db.
//
// Business function: running a sale -- start, scan, pay -- and the receipts.
// Paying changes data in two services (stock in inventory-service, the sale
// here), so this service orchestrates it as a saga (TransactionService,
// PaymentSaga, SagaResolver). Every scan is pushed to analytics-service in
// numbered batches (ScanLogPublisher). Prices and names come from
// catalog-service, loaded once.
@SpringBootApplication
public class CheckoutServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CheckoutServiceApplication.class, args);
	}
}
