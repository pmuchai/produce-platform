package com.primeroot.platform;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMigrationTests {
	@Test
	void v3PreservesExistingCatalogueAndAccessWithoutInventingStock() throws Exception {
		try (var keeper = DriverManager.getConnection(
				"jdbc:h2:mem:inventory-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(keeper, true);
			Flyway.configure().dataSource(source).target("2").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var type = UUID.randomUUID();
			var product = UUID.randomUUID();
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("INSERT INTO produce_types(id, code, name, active, created_at) VALUES (?, 'TOMATO', 'Tomatoes', TRUE, CURRENT_TIMESTAMP)", type);
			jdbc.update("""
				INSERT INTO products(id, code, name, active, created_at, produce_type_id, base_unit_id)
				VALUES (?, 'TOMATO_KG', 'Tomatoes', TRUE, CURRENT_TIMESTAMP, ?, '10000000-0000-0000-0000-000000000001')
				""", product, type);
			Flyway.configure().dataSource(source).load().migrate();
			assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, product)).isEqualTo("Tomatoes");
			assertThat(jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch)).isEqualTo("Main");
			for (var table : java.util.List.of("stock_batches", "stock_operations", "stock_movements", "stock_thresholds")) {
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM units_of_measure", Integer.class)).isEqualTo(5);
		}
	}
}
