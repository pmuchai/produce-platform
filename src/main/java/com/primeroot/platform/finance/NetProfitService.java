package com.primeroot.platform.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.primeroot.platform.access.*;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;
import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NetProfitService {
	private final AccessService access;
	private final BranchRepository branches;
	private final NamedParameterJdbcTemplate jdbc;

	public NetProfitService(AccessService access, BranchRepository branches, NamedParameterJdbcTemplate jdbc) {
		this.access = access;
		this.branches = branches;
		this.jdbc = jdbc;
	}

	public NetProfitReport report(UUID branchId, DashboardPeriod period, int page) {
		var actor = access.requirePermission(Permission.PROFIT_VIEW);
		if (page < 0 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report page");
		if (branchId != null) access.requireBranch(branchId);
		var visible = actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
		var ids = visible.stream().map(Branch::getId).filter(id -> branchId == null || id.equals(branchId)).toList();
		var options = visible.stream().map(b -> new BranchOption(b.getId(), b.getName(), b.isActive())).toList();
		if (ids.isEmpty()) return new NetProfitReport(List.of(), List.of(), page, 0, options);
		var params = new MapSqlParameterSource().addValue("branches", ids)
			.addValue("from", java.sql.Timestamp.from(period.from())).addValue("until", java.sql.Timestamp.from(period.until()))
			.addValue("limit", 100).addValue("offset", page * 100);
		var events = events(params);
		var where = " WHERE e.branch_id IN (:branches) AND e.occurred_at >= :from AND e.occurred_at < :until ";
		var grouped = "SELECT e.branch_id, e.currency, SUM(e.revenue) AS revenue, SUM(e.cogs) AS cogs, "
			+ "SUM(e.expense) AS expense, SUM(e.loss) AS loss, SUM(e.unknown_costs) AS unknown_costs FROM ("
			+ events + ") e" + where + " GROUP BY e.branch_id, e.currency";
		var unknownBranches = Set.copyOf(jdbc.query("SELECT DISTINCT branch_id FROM (" + grouped
			+ ") g WHERE currency = 'UNKNOWN' AND unknown_costs > 0", params, (rs, index) -> rs.getObject(1, UUID.class)));
		long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + grouped + ") g", params, Long.class);
		var rows = jdbc.query("SELECT g.*, b.name, b.code FROM (" + grouped + ") g JOIN branches b ON b.id = g.branch_id "
			+ "ORDER BY b.code, g.branch_id, g.currency LIMIT :limit OFFSET :offset", params, (rs, index) ->
				new NetProfitReport.Row(rs.getObject("branch_id", UUID.class), rs.getString("name"), rs.getString("code"),
					amounts(rs, unknownBranches.contains(rs.getObject("branch_id", UUID.class)))));
		var totals = jdbc.query("SELECT currency, SUM(revenue) AS revenue, SUM(cogs) AS cogs, SUM(expense) AS expense, "
			+ "SUM(loss) AS loss, SUM(unknown_costs) AS unknown_costs FROM (" + grouped
			+ ") g GROUP BY currency ORDER BY currency", params, (rs, index) -> amounts(rs, !unknownBranches.isEmpty()));
		return new NetProfitReport(rows, totals, page, count, options);
	}

	private String events(MapSqlParameterSource params) {
		String scale = "CASE WHEN b.currency IS NULL THEN 2 ";
		for (var code : jdbc.queryForList("SELECT DISTINCT currency FROM stock_batches WHERE branch_id IN (:branches) AND currency IS NOT NULL",
				params, String.class)) {
			var currency = Currency.getInstance(code);
			scale += " WHEN b.currency = '" + currency.getCurrencyCode() + "' THEN " + currency.getDefaultFractionDigits();
		}
		scale += " ELSE 2 END";
		var loss = "COALESCE(-m.cost_change, ROUND(-m.quantity * b.received_cost / b.received_quantity, " + scale + "))";
		var reversalInRange = """
			(m.reversal_of IS NOT NULL AND EXISTS (
			    SELECT 1 FROM stock_movements original
			    JOIN stock_operations original_operation ON original_operation.id = original.operation_id
			    WHERE original.id = m.reversal_of AND original.batch_id = m.batch_id AND original.kind = 'SPOILAGE'
			        AND original_operation.posted_at >= :from AND original_operation.posted_at < :until
			)) OR EXISTS (
			    SELECT 1 FROM stock_movements reversal
			    JOIN stock_operations reversal_operation ON reversal_operation.id = reversal.operation_id
			    WHERE reversal.reversal_of = m.id AND reversal.batch_id = m.batch_id AND reversal.kind = 'SPOILAGE'
			        AND reversal_operation.posted_at >= :from AND reversal_operation.posted_at < :until
			)
			""";
		return """
			SELECT branch_id, currency, occurred_at, revenue, cost AS cogs,
			    CAST(0 AS NUMERIC) AS expense, CAST(0 AS NUMERIC) AS loss, 0 AS unknown_costs FROM sales_events
			UNION ALL
			SELECT branch_id, currency, occurred_at, 0 AS revenue, 0 AS cogs, amount AS expense,
			    0 AS loss, 0 AS unknown_costs FROM expense_events
			UNION ALL
			SELECT b.branch_id, COALESCE(b.currency, 'UNKNOWN') AS currency, o.posted_at AS occurred_at,
			    0 AS revenue, 0 AS cogs, 0 AS expense,
			""" + loss + " AS loss, CASE WHEN " + loss + " IS NULL AND NOT (" + reversalInRange + """
			) THEN 1 ELSE 0 END AS unknown_costs
			FROM stock_movements m JOIN stock_batches b ON b.id = m.batch_id
			JOIN stock_operations o ON o.id = m.operation_id WHERE m.kind = 'SPOILAGE'
			UNION ALL
			SELECT t.source_id AS branch_id, COALESCE(b.currency, 'UNKNOWN') AS currency, o.posted_at AS occurred_at,
			    0 AS revenue, 0 AS cogs, 0 AS expense, a.loss_cost AS loss,
			    CASE WHEN a.loss_cost IS NULL THEN 1 ELSE 0 END AS unknown_costs
			FROM transfer_allocations a JOIN stock_transfers t ON t.id = a.transfer_id
			JOIN stock_movements m ON m.id = a.movement_id JOIN stock_batches b ON b.id = m.batch_id
			JOIN stock_operations o ON o.id = t.receive_operation_id
			WHERE a.shortage_quantity + a.damaged_quantity > 0
			""";
	}

	private static NetProfitReport.Total amounts(ResultSet rs, boolean unknownCurrencyLoss) throws SQLException {
		var code = rs.getString("currency");
		long unknown = rs.getLong("unknown_costs");
		if ("UNKNOWN".equals(code)) return new NetProfitReport.Total(code, null, null, null, null, null, null, unknown);
		var revenue = money(rs.getBigDecimal("revenue"), code);
		var cogs = money(rs.getBigDecimal("cogs"), code);
		var expenses = money(rs.getBigDecimal("expense"), code);
		var losses = money(rs.getBigDecimal("loss"), code);
		var gross = revenue.subtract(cogs);
		return new NetProfitReport.Total(code, revenue, cogs, gross, expenses, losses,
			unknown == 0 && !unknownCurrencyLoss ? gross.subtract(expenses).subtract(losses) : null, unknown);
	}

	private static BigDecimal money(BigDecimal value, String currency) {
		return (value == null ? BigDecimal.ZERO : value)
			.setScale(Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}
}
