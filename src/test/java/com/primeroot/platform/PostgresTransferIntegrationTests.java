package com.primeroot.platform;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
class PostgresTransferIntegrationTests extends TransferIntegrationTests {
	@DynamicPropertySource
	static void postgres(DynamicPropertyRegistry registry) {
		PostgresTestDatabase.configure(registry, "transfers_e2e");
	}
}
