package com.primeroot.platform;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SaleDateMigrationTests {
	@Test
	void existingSalesRetainUtcDatesPostingTimesAndRefundDatesAfterUpgrade() throws Exception {
		try (var connection = DriverManager.getConnection(
				"jdbc:h2:mem:sale-date-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(connection, true);
			Flyway.configure().dataSource(source).target("11").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var actor = UUID.randomUUID();
			var sale = UUID.randomUUID();
			var refund = UUID.randomUUID();
			var postedAt = OffsetDateTime.parse("2026-10-30T23:59:59Z");
			var refundedAt = OffsetDateTime.parse("2026-11-01T01:00:00Z");
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", actor);
			jdbc.update("""
				INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at)
				VALUES (?, ?, ?, 'SALE', 'sale-hash', ?)
				""", sale, branch, actor, postedAt);
			jdbc.update("""
				INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at)
				VALUES (?, ?, ?, 'SALE_VOID', 'void-hash', ?)
				""", refund, branch, actor, refundedAt);
			jdbc.update("""
				INSERT INTO sales(id, branch_id, seller_id, seller_name, branch_name, currency, total, cost, payment_method, void_operation_id)
				VALUES (?, ?, ?, 'Admin', 'Main', 'KES', 100, 60, 'CASH', ?)
				""", sale, branch, actor, refund);
			jdbc.execute("SET TIME ZONE '+03:00'");
			Flyway.configure().dataSource(source).load().migrate();
			assertThat(jdbc.queryForObject("SELECT sale_date FROM sales WHERE id = ?", LocalDate.class, sale)).isEqualTo(LocalDate.of(2026, 10, 30));
			assertThat(jdbc.queryForObject("SELECT occurred_at FROM sales_events WHERE direction = 1", OffsetDateTime.class).toInstant())
				.isEqualTo(postedAt.toInstant());
			assertThat(jdbc.queryForObject("SELECT occurred_at FROM sales_events WHERE direction = -1", OffsetDateTime.class).toInstant())
				.isEqualTo(refundedAt.toInstant());
			assertThat(jdbc.queryForObject("SELECT SUM(revenue) FROM sales_events", BigDecimal.class)).isEqualByComparingTo("0");
			jdbc.update("UPDATE sales SET sale_date = DATE '2026-10-29' WHERE id = ?", sale);
			assertThat(jdbc.queryForObject("SELECT occurred_at FROM sales_events WHERE direction = 1", OffsetDateTime.class).toInstant())
				.isEqualTo(OffsetDateTime.parse("2026-10-29T00:00:00Z").toInstant());
			assertThat(jdbc.queryForObject("SELECT posted_at FROM stock_operations WHERE id = ?", OffsetDateTime.class, sale).toInstant())
				.isEqualTo(postedAt.toInstant());
		}
	}
}
