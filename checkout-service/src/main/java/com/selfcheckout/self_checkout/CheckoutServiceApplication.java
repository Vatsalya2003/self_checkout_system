package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// CHECKOUT-SERVICE entry point. Its own jar, its own process, port 8082.
//
// Business function: the transaction lifecycle -- start, scan, pay -- together
// with everything that must happen inside the payment's own database
// transaction: the stock decrement under row locks and the low-stock alert.
// Splitting either of those into another service would need a distributed
// transaction, so they stay here.
//
// It also publishes every scan to scan_log, which is how analytics-service
// hears about scans without the two services ever calling each other.
//
// Open baskets live in this process's memory (OpenTransactionStore), which
// pins checkout-service to ONE instance: start, scan and pay for a basket must
// all reach the same process.
@SpringBootApplication
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class CheckoutServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CheckoutServiceApplication.class, args);
	}
}
