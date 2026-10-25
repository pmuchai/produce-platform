package com.primeroot.platform;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueMigrationTests {
	@Test
	void v2PreservesExistingStaffBranchesAndAuditWhileAddingCatalogueAndSeedUnits() throws Exception {
		try (var keeper = DriverManager.getConnection(
				"jdbc:h2:mem:catalogue-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
			var source = new SingleConnectionDataSource(keeper, true);
			Flyway.configure().dataSource(source).target("1").load().migrate();
			var jdbc = new JdbcTemplate(source);
			var branch = UUID.randomUUID();
			var staff = UUID.randomUUID();
			jdbc.update("INSERT INTO branches(id, code, name, active, created_at) VALUES (?, 'MAIN', 'Main', TRUE, CURRENT_TIMESTAMP)", branch);
			jdbc.update("""
				INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
				VALUES (?, 'admin', 'Admin', 'https://identity.example.test', 'existing-subject', TRUE, TRUE, CURRENT_TIMESTAMP)
				""", staff);
			jdbc.update("INSERT INTO user_branches(user_id, branch_id) VALUES (?, ?)", staff, branch);
			jdbc.update("INSERT INTO audit_events(id, actor_id, action, target_type, target_id, occurred_at) VALUES (?, ?, 'STAFF_APPROVED', 'USER', ?, CURRENT_TIMESTAMP)",
				UUID.randomUUID(), staff, staff);
			Flyway.configure().dataSource(source).load().migrate();
			assertThat(jdbc.queryForObject("SELECT subject FROM staff_users WHERE id = ?", String.class, staff))
				.isEqualTo("existing-subject");
			assertThat(jdbc.queryForObject("SELECT branch_id FROM user_branches WHERE user_id = ?", UUID.class, staff)).isEqualTo(branch);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM units_of_measure", Integer.class)).isEqualTo(5);
			assertThat(jdbc.queryForObject("SELECT base_factor FROM units_of_measure WHERE code = 'G'", java.math.BigDecimal.class))
				.isEqualByComparingTo("0.001");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isZero();
		}
	}
}
