package com.selfcheckout.self_checkout;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// Entry point. Starting this also rebuilds the database, because
// schema.sql + data.sql run on every boot (spring.sql.init.mode=always).
// So "restart the server" IS the reset procedure for this project.
//
// @SpringBootApplication bundles three things:
//   @Configuration       - this class can define beans
//   @EnableAutoConfiguration - wire up Tomcat, JPA, Hikari from the classpath
//   @ComponentScan       - find @RestController/@Service/@Repository below
//                          this package, which is why everything must live
//                          under com.selfcheckout.self_checkout
@SpringBootApplication
// Records annotated with @ConfigurationProperties aren't picked up by
// component scanning, so they have to be registered explicitly here.
@EnableConfigurationProperties(SelfCheckoutProperties.class)
public class SelfCheckoutApplication {

	public static void main(String[] args) {
		SpringApplication.run(SelfCheckoutApplication.class, args);
	}

}
