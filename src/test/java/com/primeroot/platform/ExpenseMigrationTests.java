package com.primeroot.platform;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseMigrationTests {
	@Test
	void upgradePreservesExistingDataAndPaidDatesRemainUtcIndependentOfDatabaseTimeZone() throws Exception {
		try (var connection = DriverManager.getConnection(
				"jdbc:h2:mem:expenses-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(connection, true);
			Flyway.configure().dataSource(source).target("9").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var actor = UUID.randomUUID();
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", actor);
			Flyway.configure().dataSource(source).load().migrate();
			jdbc.execute("SET TIME ZONE '+03:00'");
			var id = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO expenses(id, branch_id, branch_name, actor_id, actor_name, posted_at, paid_date,
					category, description, currency, amount, payment_method, request_hash)
				VALUES (?, ?, 'Main', ?, 'Admin', CURRENT_TIMESTAMP, DATE '2026-10-31', 'RENT', 'Rent', 'KES', 15.25, 'CASH', 'hash')
				""", id, branch, actor);
			var timestamp = jdbc.queryForObject("SELECT occurred_at FROM expense_events WHERE expense_id = ?", OffsetDateTime.class, id);
			assertThat(timestamp.toInstant()).isEqualTo(Instant.parse("2026-10-31T00:00:00Z"));
			assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM expense_events", BigDecimal.class)).isEqualByComparingTo("15.25");
			assertThat(jdbc.queryForObject("SELECT subject FROM staff_users WHERE id = ?", String.class, actor)).isEqualTo("existing");
			assertThatThrownBy(() -> jdbc.update("UPDATE expenses SET amount = -1 WHERE id = ?", id))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update("UPDATE expenses SET void_request_id = ? WHERE id = ?", UUID.randomUUID(), id))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		}
	}
}
