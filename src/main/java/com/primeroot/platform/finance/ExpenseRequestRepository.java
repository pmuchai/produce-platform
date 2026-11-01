package com.primeroot.platform.finance;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExpenseRequestRepository {
	public enum Reservation { RESERVED, REPLAY, CONFLICT }

	private final JdbcTemplate jdbc;
	private final String insertSql;

	public ExpenseRequestRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.insertSql = insertSql(jdbc);
	}

	public Reservation reserve(UUID requestId, String action, String hash) {
		var inserted = jdbc.update(insertSql, requestId, action, hash);
		if (inserted > 0) return Reservation.RESERVED;
		var existing = jdbc.query("""
			SELECT action, request_hash FROM expense_request_ids WHERE request_id = ?
			""", (result, row) -> new ReservationIdentity(result.getString("action"), result.getString("request_hash")),
			requestId).stream().findFirst();
		if (existing.isPresent() && existing.get().action().equals(action) && existing.get().hash().equals(hash)) {
			return Reservation.REPLAY;
		}
		return Reservation.CONFLICT;
	}

	private static String insertSql(JdbcTemplate jdbc) {
		try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
			if (connection.getMetaData().getDatabaseProductName().equals("H2")) {
				return """
					MERGE INTO expense_request_ids t
					USING (VALUES (CAST(? AS UUID), ?, ?)) s(request_id, action, request_hash)
					ON t.request_id = s.request_id
					WHEN NOT MATCHED THEN INSERT (request_id, action, request_hash)
						VALUES (s.request_id, s.action, s.request_hash)
					""";
			}
			return """
				INSERT INTO expense_request_ids (request_id, action, request_hash) VALUES (?, ?, ?)
				ON CONFLICT (request_id) DO NOTHING
				""";
		}
		catch (SQLException exception) {
			throw new IllegalStateException("Could not determine expense request database", exception);
		}
	}

	private record ReservationIdentity(String action, String hash) {}
}
