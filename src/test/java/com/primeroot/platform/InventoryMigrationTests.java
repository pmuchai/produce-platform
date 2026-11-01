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
	void v8PreservesExistingLedgerAndEnforcesTransferRouteStateAndFinalAccounting() throws Exception {
		try (var keeper = DriverManager.getConnection(
				"jdbc:h2:mem:transfer-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(keeper, true);
			Flyway.configure().dataSource(source).target("7").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var destination = UUID.randomUUID();
			var actor = UUID.randomUUID();
			var type = UUID.randomUUID();
			var product = UUID.randomUUID();
			var receipt = UUID.randomUUID();
			var batch = UUID.randomUUID();
			for (var id : java.util.List.of(branch, destination)) {
				jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, ?, 'Branch', TRUE, CURRENT_TIMESTAMP)",
					id, id.equals(branch) ? "SOURCE" : "DESTINATION");
			}
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing-subject', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", actor);
			jdbc.update("INSERT INTO produce_types(id, code, name, active, created_at) VALUES (?, 'ONION', 'Onions', TRUE, CURRENT_TIMESTAMP)", type);
			jdbc.update("""
				INSERT INTO products(id, code, name, active, created_at, produce_type_id, base_unit_id)
				VALUES (?, 'ONION_KG', 'Onions', TRUE, CURRENT_TIMESTAMP, ?, '10000000-0000-0000-0000-000000000001')
				""", product, type);
			jdbc.update("INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at) VALUES (?, ?, ?, 'RECEIPT', 'legacy', CURRENT_TIMESTAMP)",
				receipt, branch, actor);
			jdbc.update("""
				INSERT INTO stock_batches(id, operation_id, branch_id, product_id, lot_code, received_date,
					input_unit_id, input_quantity, received_quantity, conversion_numerator, conversion_denominator,
					measured, quantity, created_at, currency, purchase_unit_cost, received_cost, remaining_cost)
				VALUES (?, ?, ?, ?, 'LEGACY', CURRENT_DATE, '10000000-0000-0000-0000-000000000001',
					10, 10, 1, 1, FALSE, 10, CURRENT_TIMESTAMP, 'KES', 2, 20, 20)
				""", batch, receipt, branch, product);
			jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity, cost_change) VALUES (?, ?, ?, 'RECEIPT', 10, 20)",
				UUID.randomUUID(), receipt, batch);
			Flyway.configure().dataSource(source).load().migrate();
			assertThat(jdbc.queryForObject("SELECT remaining_cost FROM stock_batches", java.math.BigDecimal.class)).isEqualByComparingTo("20");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfers", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_allocations", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT source_batch_id FROM stock_batches", UUID.class)).isNull();
			var dispatch = UUID.randomUUID();
			var arrival = UUID.randomUUID();
			var movement = UUID.randomUUID();
			var allocation = UUID.randomUUID();
			jdbc.update("INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at) VALUES (?, ?, ?, 'TRANSFER_OUT', 'dispatch', CURRENT_TIMESTAMP)",
				dispatch, branch, actor);
			jdbc.update("INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at) VALUES (?, ?, ?, 'TRANSFER_IN', 'arrival', CURRENT_TIMESTAMP)",
				arrival, destination, actor);
			assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO stock_transfers(id, source_id, destination_id, source_name, destination_name, status)
				VALUES (?, ?, ?, 'Source', 'Destination', 'IN_TRANSIT')
				""", dispatch, branch, branch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			jdbc.update("""
				INSERT INTO stock_transfers(id, source_id, destination_id, source_name, destination_name, status)
				VALUES (?, ?, ?, 'Source', 'Destination', 'IN_TRANSIT')
				""", dispatch, branch, destination);
			jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity, cost_change) VALUES (?, ?, ?, 'TRANSFER_OUT', -10, -20)",
				movement, dispatch, batch);
			jdbc.update("""
				INSERT INTO transfer_allocations(id, transfer_id, movement_id, product_name, product_code, produce_type, base_unit, quantity, cost)
				VALUES (?, ?, ?, 'Onions', 'ONION_KG', 'Onions', 'KG', 10, 20)
				""", allocation, dispatch, movement);
			assertThatThrownBy(() -> jdbc.update("UPDATE stock_transfers SET status = 'RECEIVED' WHERE id = ?", dispatch))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update("UPDATE transfer_allocations SET received_quantity = 0 WHERE id = ?", allocation))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update("""
				UPDATE transfer_allocations SET received_quantity = 0, shortage_quantity = 8, damaged_quantity = 1,
					received_cost = 0, loss_cost = 20, reason = 'Lost' WHERE id = ?
				""", allocation)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update("""
				UPDATE transfer_allocations SET received_quantity = 0, shortage_quantity = 8, damaged_quantity = 2,
					received_cost = 0, loss_cost = 20 WHERE id = ?
				""", allocation)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			jdbc.update("""
				UPDATE transfer_allocations SET received_quantity = 0, shortage_quantity = 8, damaged_quantity = 2,
					received_cost = 0, loss_cost = 20, reason = 'Full loss' WHERE id = ?
				""", allocation);
			jdbc.update("UPDATE stock_transfers SET status = 'RECEIVED', receive_operation_id = ? WHERE id = ?", arrival, dispatch);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_events", Integer.class)).isZero();
		}
	}

	@Test
	void v5InitializesOpeningCarryingValuesInTheirOwnCurrencyWithoutInventingSalesOrLegacyCosts() throws Exception {
		try (var keeper = DriverManager.getConnection(
				"jdbc:h2:mem:sales-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(keeper, true);
			Flyway.configure().dataSource(source).target("4").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var actor = UUID.randomUUID();
			var type = UUID.randomUUID();
			var product = UUID.randomUUID();
			var receipt = UUID.randomUUID();
			var correction = UUID.randomUUID();
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing-subject', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", actor);
			jdbc.update("INSERT INTO produce_types(id, code, name, active, created_at) VALUES (?, 'ONION', 'Onions', TRUE, CURRENT_TIMESTAMP)", type);
			jdbc.update("""
				INSERT INTO products(id, code, name, active, created_at, produce_type_id, base_unit_id)
				VALUES (?, 'ONION_KG', 'Onions', TRUE, CURRENT_TIMESTAMP, ?, '10000000-0000-0000-0000-000000000001')
				""", product, type);
			for (var operation : java.util.List.of(receipt, correction)) {
				jdbc.update("INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at) VALUES (?, ?, ?, ?, 'legacy', CURRENT_TIMESTAMP)",
					operation, branch, actor, operation.equals(receipt) ? "RECEIPT" : "ADJUSTMENT");
			}
			var openings = java.util.List.of(new Opening("KES", "5000", "1000001", "1", "0.00"),
				new Opening("JPY", "1", "3", "2", "1"), new Opening("BHD", "0.001", "3", "1", "0.000"),
				new Opening(null, null, "10", "3", null));
			for (int index = 0; index < openings.size(); index++) {
				var row = openings.get(index);
				var batch = UUID.randomUUID();
				jdbc.update("""
					INSERT INTO stock_batches(id, operation_id, branch_id, product_id, lot_code, received_date,
					    input_unit_id, input_quantity, received_quantity, measured, quantity, created_at,
					    currency, purchase_unit_cost, received_cost)
					VALUES (?, ?, ?, ?, ?, CURRENT_DATE, '10000000-0000-0000-0000-000000000004',
					    1, ?, TRUE, ?, CURRENT_TIMESTAMP, ?, ?, ?)
					""", batch, receipt, branch, product, "OPEN-" + index, new java.math.BigDecimal(row.received()),
					new java.math.BigDecimal(row.remaining()), row.currency(), row.cost(), row.cost());
				jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity) VALUES (?, ?, ?, 'RECEIPT', ?)",
					UUID.randomUUID(), receipt, batch, new java.math.BigDecimal(row.received()));
				jdbc.update("INSERT INTO stock_movements(id, operation_id, batch_id, kind, quantity) VALUES (?, ?, ?, 'ADJUSTMENT', ?)",
					UUID.randomUUID(), correction, batch, new java.math.BigDecimal(row.remaining()).subtract(new java.math.BigDecimal(row.received())));
			}
			Flyway.configure().dataSource(source).load().migrate();
			for (int index = 0; index < openings.size(); index++) {
				var cost = jdbc.queryForObject("SELECT remaining_cost FROM stock_batches WHERE lot_code = ?",
					java.math.BigDecimal.class, "OPEN-" + index);
				if (openings.get(index).expected() == null) assertThat(cost).isNull();
				else assertThat(cost).isEqualByComparingTo(openings.get(index).expected());
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements", Integer.class)).isEqualTo(8);
			for (var table : java.util.List.of("sales", "sale_lines", "sale_allocations")) {
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
			}
		}
	}

	private record Opening(String currency, String cost, String received, String remaining, String expected) {
	}

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
