package com.selfcheckout.self_checkout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Smoke test: boots checkout-service's whole application context, so this
// fails if any bean is misconfigured, any @Query has a syntax error, or the
// database is unreachable. Since Week 4 the services never create the schema
// themselves, so run db/reset.sh before the tests (scripts/test-all.sh does).
@SpringBootTest
class CheckoutServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}
