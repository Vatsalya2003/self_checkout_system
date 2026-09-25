package com.selfcheckout.self_checkout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Smoke test: @SpringBootTest boots the whole application context, so this
// fails if any bean is misconfigured, any @Query has a syntax error, or the
// database is unreachable. It needs Postgres running, because starting the
// context also runs schema.sql and data.sql.
@SpringBootTest
class SelfCheckoutApplicationTests {

	@Test
	void contextLoads() {
	}

}
