package com.selfcheckout.self_checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// CATALOG-SERVICE entry point. Its own jar, its own process, port 8081, and
// since Week 5 its own database, catalog_db.
//
// Business function: the product catalog. One endpoint, GET /items, answered
// from memory (JdbcCatalogCache). It reads catalog_db once and never writes.
//
// The other services get the catalog from this endpoint too -- they can no
// longer read the items table themselves, because it is in a database only
// catalog_svc may connect to.
@SpringBootApplication
public class CatalogServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CatalogServiceApplication.class, args);
	}
}
