package com.primeroot.platform;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Test
	void v4PreservesExistingBatchesAndMovementsWithUnknownRatherThanZeroCosts() throws Exception {
		try (var keeper = DriverManager.getConnection(
				"jdbc:h2:mem:receipt-cost-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(keeper, true);
			Flyway.configure().dataSource(source).target("3").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var staff = UUID.randomUUID();
			var type = UUID.randomUUID();
			var product = UUID.randomUUID();
			var operation = UUID.randomUUID();
			var adjustment = UUID.randomUUID();
			var batch = UUID.randomUUID();
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing-subject', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", staff);
			jdbc.update("INSERT INTO produce_types(id, code, name, active, created_at) VALUES (?, 'ONION', 'Onions', TRUE, CURRENT_TIMESTAMP)", type);
			jdbc.update("""
				INSERT INTO products(id, code, name, active, created_at, produce_type_id, base_unit_id)
				VALUES (?, 'ONION_KG', 'Onions', TRUE, CURRENT_TIMESTAMP, ?, '10000000-0000-0000-0000-000000000001')
				""", product, type);
			jdbc.update("""
				INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, source, posted_at)
				VALUES (?, ?, ?, 'RECEIPT', 'legacy-receipt', 'Existing farm', CURRENT_TIMESTAMP)
				""", operation, branch, staff);
			jdbc.update("""
				INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, notes, posted_at)
				VALUES (?, ?, ?, 'ADJUSTMENT', 'legacy-adjustment', 'Existing correction', CURRENT_TIMESTAMP)
				""", adjustment, branch, staff);
			jdbc.update("""
				INSERT INTO stock_batches(id, operation_id, branch_id, product_id, lot_code, received_date,
				    input_unit_id, input_quantity, received_quantity, conversion_numerator, conversion_denominator,
				    measured, quantity, created_at)
				VALUES (?, ?, ?, ?, 'EXISTING_LOT', CURRENT_DATE, '10000000-0000-0000-0000-000000000001',
				    1000, 1000, 1, 1, FALSE, 250, CURRENT_TIMESTAMP)
				""", batch, operation, branch, product);
			jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity) VALUES (?, ?, ?, 'RECEIPT', 1000)",
				UUID.randomUUID(), operation, batch);
			jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity) VALUES (?, ?, ?, 'ADJUSTMENT', -750)",
				UUID.randomUUID(), adjustment, batch);
			Flyway.configure().dataSource(source).load().migrate();
			assertThat(jdbc.queryForObject("SELECT quantity FROM stock_batches WHERE id = ?", java.math.BigDecimal.class, batch))
				.isEqualByComparingTo("250");
			assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM stock_movements WHERE batch_id = ?", java.math.BigDecimal.class, batch))
				.isEqualByComparingTo("250");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements", Integer.class)).isEqualTo(2);
			var row = jdbc.queryForMap("SELECT currency, purchase_unit_cost, received_cost FROM stock_batches WHERE id = ?", batch);
			assertThat(row.values()).containsOnlyNulls();
			assertThatThrownBy(() ->
				jdbc.update("UPDATE stock_batches SET currency = 'KES' WHERE id = ?", batch))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThatThrownBy(() ->
				jdbc.update("UPDATE stock_batches SET currency = 'KES', purchase_unit_cost = -1, received_cost = -1000 WHERE id = ?", batch))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		}
	}
}
