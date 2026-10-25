package com.primeroot.platform;

import org.springframework.test.context.DynamicPropertyRegistry;

final class PostgresTestDatabase {
	private PostgresTestDatabase() {
	}

	static void configure(DynamicPropertyRegistry registry, String schema) {
		var url = System.getenv("PG_TEST_URL");
		if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/primeroot_phase4_test")) {
			throw new IllegalArgumentException("PG_TEST_URL must name the disposable loopback database primeroot_phase4_test, without query parameters");
		}
		var username = System.getenv("PG_TEST_USERNAME");
		var password = System.getenv("PG_TEST_PASSWORD");
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IllegalArgumentException("Explicit disposable PostgreSQL test credentials are required");
		}
		registry.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
		registry.add("spring.datasource.username", () -> username);
		registry.add("spring.datasource.password", () -> password);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.flyway.schemas", () -> schema);
		registry.add("spring.flyway.default-schema", () -> schema);
		registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
	}
}
