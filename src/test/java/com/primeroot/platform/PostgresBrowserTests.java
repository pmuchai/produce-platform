package com.primeroot.platform;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
class PostgresBrowserTests extends CatalogueBrowserTests {
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void resetDisposableBrowserFixtures() {
		for (var table : java.util.List.of("audit_events", "staff_access_requests", "sale_allocations", "sale_lines", "sales",
				"stock_movements", "stock_batches", "stock_operations", "stock_thresholds", "product_packaging",
				"products", "product_classifications", "produce_types", "user_branches", "staff_users", "branches")) {
			jdbc.update("DELETE FROM " + table);
		}
	}

	@DynamicPropertySource
	static void postgres(DynamicPropertyRegistry registry) {
		PostgresTestDatabase.configure(registry, "browser_e2e");
	}
}
