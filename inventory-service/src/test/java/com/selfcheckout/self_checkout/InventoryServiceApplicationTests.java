package com.selfcheckout.self_checkout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Smoke test: boots inventory-service's whole application context, so this fails if any
// bean is misconfigured, any @Query has a syntax error, or the database is
// unreachable. The services never create the schema themselves, so run
// db/reset.sh before the tests.
@SpringBootTest
class InventoryServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}
