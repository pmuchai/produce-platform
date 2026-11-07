package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.BranchRepository;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;
import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SalesReportService {
	private final AccessService access;
	private final BranchRepository branches;
	private final NamedParameterJdbcTemplate jdbc;

	public SalesReportService(AccessService access, BranchRepository branches, NamedParameterJdbcTemplate jdbc) {
		this.access = access;
		this.branches = branches;
		this.jdbc = jdbc;
	}

	public SalesReports.Report report(SalesReports.Kind kind, UUID branchId, DashboardPeriod period, int page) {
		return report(kind, branchId, period, page, false);
	}

	public SalesReports.Report chart(SalesReports.Kind kind, DashboardPeriod period) {
		if (kind != SalesReports.Kind.DAILY && kind != SalesReports.Kind.STAFF) {
			throw new IllegalArgumentException("Only daily sales and salesperson charts are supported");
		}
		return report(kind, null, period, 0, true);
	}

	public List<SalesReports.QuantityTotal> quantities(DashboardPeriod period) {
		record Key(String currency, String unit) {}
		var totals = new java.util.LinkedHashMap<Key, BigDecimal>();
		for (var row : report(SalesReports.Kind.PRODUCTS, null, period, 0, true).rows()) {
			totals.merge(new Key(row.currency(), row.unit()), row.quantity(), BigDecimal::add);
		}
		return totals.entrySet().stream().map(entry -> new SalesReports.QuantityTotal(entry.getKey().currency(),
			entry.getKey().unit(), entry.getValue())).sorted(java.util.Comparator.comparing(SalesReports.QuantityTotal::currency)
				.thenComparing(SalesReports.QuantityTotal::unit)).toList();
	}

	private SalesReports.Report report(SalesReports.Kind kind, UUID branchId, DashboardPeriod period, int page, boolean chart) {
		var actor = access.currentUser();
		var permissions = access.permissions();
		boolean profit = permissions.contains(Permission.PROFIT_VIEW);
		if (kind.isFinancial()) access.requirePermission(Permission.PROFIT_VIEW);
		else if (kind == SalesReports.Kind.LOSSES) access.requirePermission(Permission.STOCK_VIEW);
		else if (!permissions.contains(Permission.SALES_VIEW) && !permissions.contains(Permission.SALE_CREATE)) {
			throw new AccessDeniedException("Sales viewing or selling permission is required");
		}
		if (page < 0 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report page");
		if (branchId != null) access.requireBranch(branchId);
		var visible = actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
		var ids = visible.stream().map(Branch::getId).filter(id -> branchId == null || id.equals(branchId)).toList();
		boolean own = !kind.isStock() && !kind.isFinancial() && !permissions.contains(Permission.SALES_VIEW);
		var options = visible.stream().map(branch -> new BranchOption(branch.getId(), branch.getName(), branch.isActive())).toList();
		if (ids.isEmpty()) return new SalesReports.Report(kind, List.of(), 0, page, List.of(), List.of(), own, profit, options, 0, 0);
		var params = new MapSqlParameterSource().addValue("branches", ids)
			.addValue("from", java.sql.Timestamp.from(period.from())).addValue("until", java.sql.Timestamp.from(period.until()))
			.addValue("limit", 100).addValue("offset", page * 100);
		String where = " e.branch_id IN (:branches) AND e.occurred_at >= :from AND e.occurred_at < :until ";
		if (own) {
			where += " AND e.seller_id = :seller ";
			params.addValue("seller", actor.getId());
		}
		String query = kind.isStock() ? stockQuery(kind, params) : salesQuery(kind, where, chart);
		var count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + query + ") report_count", params, Long.class);
		String ordering = " ORDER BY label, detail, currency, record_id";
		if (kind.isStock()) ordering += ", sort_product_id, sort_branch_id";
		var rows = jdbc.query(query + ordering + (chart ? "" : " LIMIT :limit OFFSET :offset"), params,
			(rs, index) -> row(rs, profit));
		var totals = kind.isStock() ? stockTotals(query, params, profit) : totals(where, params, profit);
		var activity = kind.isStock() ? new long[] {0, 0} : jdbc.queryForObject(
			"SELECT COUNT(DISTINCT e.seller_id), COUNT(DISTINCT e.branch_id) FROM sales_events e WHERE " + where,
			params, (rs, index) -> new long[] {rs.getLong(1), rs.getLong(2)});
		var previous = List.<SalesReports.Summary>of();
		if (!kind.isStock()) {
			params.addValue("from", java.sql.Timestamp.from(period.previousFrom()))
				.addValue("until", java.sql.Timestamp.from(period.from()));
			previous = totals(where, params, profit);
		}
		return new SalesReports.Report(kind, rows, count, page, totals, previous, own, profit, options, activity[0], activity[1]);
	}

	private String salesQuery(SalesReports.Kind kind, String where, boolean chart) {
		String label;
		String detail = "''";
		String joins = "";
		String groups;
		String quantity = "CAST(NULL AS NUMERIC)";
		String unit = "''";
		String revenue = "SUM(e.revenue)";
		String cost = "SUM(e.cost)";
		if (kind == SalesReports.Kind.SALES) {
			return """
				SELECT e.sale_id AS record_id, CAST(e.occurred_at AT TIME ZONE 'UTC' AS VARCHAR) AS label,
				CONCAT(CASE WHEN e.direction = 1 THEN 'Sale / ' ELSE 'Void / ' END, s.customer, ' / ', b.name, ' / ', u.full_name) AS detail,
				e.currency, '' AS unit, CAST(NULL AS NUMERIC) AS quantity,
				CASE WHEN e.direction = 1 THEN 1 ELSE 0 END AS receipts,
				CASE WHEN e.direction = -1 THEN 1 ELSE 0 END AS voids,
				e.revenue, e.cost, 0 AS unknown_costs
				FROM sales_events e JOIN sales s ON s.id = e.sale_id
				JOIN branches b ON b.id = e.branch_id JOIN staff_users u ON u.id = e.seller_id WHERE
				""" + where;
		}
		switch (kind) {
			case DAILY -> {
				label = "CAST(CAST(e.occurred_at AT TIME ZONE 'UTC' AS DATE) AS VARCHAR)";
				groups = label + ", e.currency";
			}
			case PRODUCTS -> {
				joins = " JOIN sale_lines l ON l.sale_id = e.sale_id ";
				label = "l.product_name";
				detail = "l.product_code";
				unit = "l.base_unit";
				quantity = "SUM(l.base_quantity * e.direction)";
				revenue = "SUM(l.total * e.direction)";
				cost = "SUM(l.cost * e.direction)";
				groups = "l.product_id, l.product_name, l.product_code, l.base_unit, e.currency";
			}
			case BRANCHES, PROFIT -> {
				joins = " JOIN branches b ON b.id = e.branch_id ";
				label = "b.name";
				detail = "b.code";
				groups = "b.id, b.name, b.code, e.currency";
			}
			case STAFF -> {
				joins = " JOIN staff_users u ON u.id = e.seller_id ";
				label = "u.full_name";
				detail = "u.username";
				groups = "u.id, u.full_name, u.username, e.currency";
				if (!chart) {
					joins += " JOIN branches b ON b.id = e.branch_id ";
					detail = "CONCAT(u.username, ' / ', b.name)";
					groups += ", b.id, b.name";
				}
			}
			case CUSTOMERS -> {
				joins = " JOIN sales s ON s.id = e.sale_id ";
				label = "s.customer";
				groups = "s.customer, e.currency";
			}
			case PAYMENTS -> {
				label = "e.payment_method";
				groups = "e.payment_method, e.currency";
			}
			default -> throw new IllegalArgumentException("Not a sales aggregation: " + kind);
		}
		String receipts = kind == SalesReports.Kind.PRODUCTS
			? "COUNT(DISTINCT CASE WHEN e.direction = 1 THEN e.sale_id END)"
			: "SUM(CASE WHEN e.direction = 1 THEN 1 ELSE 0 END)";
		String voids = kind == SalesReports.Kind.PRODUCTS
			? "COUNT(DISTINCT CASE WHEN e.direction = -1 THEN e.sale_id END)"
			: "SUM(CASE WHEN e.direction = -1 THEN 1 ELSE 0 END)";
		return "SELECT CAST(NULL AS UUID) AS record_id, " + label + " AS label, " + detail + " AS detail, e.currency, "
			+ unit + " AS unit, " + quantity + " AS quantity, " + receipts + " AS receipts, " + voids
			+ " AS voids, " + revenue + " AS revenue, " + cost + " AS cost, 0 AS unknown_costs FROM sales_events e "
			+ joins + " WHERE " + where + " GROUP BY " + groups;
	}

	private String stockQuery(SalesReports.Kind kind, MapSqlParameterSource params) {
		String scale = "CASE WHEN b.currency IS NULL THEN 2 ";
		for (var code : jdbc.queryForList("SELECT DISTINCT currency FROM stock_batches WHERE branch_id IN (:branches) AND currency IS NOT NULL",
				params, String.class)) {
			var currency = Currency.getInstance(code);
			scale += " WHEN b.currency = '" + currency.getCurrencyCode() + "' THEN " + currency.getDefaultFractionDigits();
		}
		scale += " ELSE 2 END";
		String value = kind == SalesReports.Kind.VALUATION ? "CASE WHEN b.received_cost IS NOT NULL THEN ROUND(b.remaining_cost, " + scale + ") END"
			: "COALESCE(-m.cost_change, ROUND(-m.quantity * b.received_cost / b.received_quantity, " + scale + "))";
		String query = """
			SELECT CAST(NULL AS UUID) AS record_id, p.name AS label, br.name AS detail,
			p.id AS sort_product_id, br.id AS sort_branch_id,
			COALESCE(b.currency, 'UNKNOWN') AS currency, u.code AS unit,
			""" + (kind == SalesReports.Kind.VALUATION ? "SUM(b.quantity)" : "SUM(-m.quantity)") + """
			 AS quantity, 0 AS receipts, 0 AS voids, CAST(NULL AS NUMERIC) AS revenue,
			SUM(
			""" + value + """
			) AS cost,
			SUM(CASE WHEN b.received_cost IS NULL THEN 1 ELSE 0 END) AS unknown_costs
			FROM stock_batches b JOIN branches br ON br.id = b.branch_id
			JOIN products p ON p.id = b.product_id JOIN units_of_measure u ON u.id = p.base_unit_id
			""";
		if (kind == SalesReports.Kind.VALUATION) {
			query += " WHERE b.branch_id IN (:branches) AND b.quantity > 0 ";
		}
		else {
			query += """
				 JOIN stock_movements m ON m.batch_id = b.id JOIN stock_operations o ON o.id = m.operation_id
				 WHERE b.branch_id IN (:branches) AND m.kind = 'SPOILAGE'
				 AND o.posted_at >= :from AND o.posted_at < :until
				""";
		}
		return query + " GROUP BY p.id, p.name, br.id, br.name, b.currency, u.code";
	}

	private List<SalesReports.Summary> totals(String where, MapSqlParameterSource params, boolean profit) {
		return jdbc.query("""
			SELECT e.currency, SUM(CASE WHEN e.direction = 1 THEN 1 ELSE 0 END) AS receipts,
			SUM(CASE WHEN e.direction = -1 THEN 1 ELSE 0 END) AS voids, SUM(e.revenue) AS revenue, SUM(e.cost) AS cost
			FROM sales_events e WHERE
			""" + where + " GROUP BY e.currency ORDER BY e.currency", params, (rs, index) -> {
				var code = rs.getString("currency");
				var revenue = money(rs.getBigDecimal("revenue"), code);
				var cost = money(rs.getBigDecimal("cost"), code);
				return new SalesReports.Summary(code, rs.getLong("receipts"), rs.getLong("voids"),
					revenue, profit ? cost : null, profit ? revenue.subtract(cost) : null);
			});
	}

	private List<SalesReports.Summary> stockTotals(String query, MapSqlParameterSource params, boolean profit) {
		return jdbc.query("SELECT currency, SUM(cost) AS cost FROM (" + query + ") values_report GROUP BY currency ORDER BY currency",
			params, (rs, index) -> new SalesReports.Summary(rs.getString("currency"), 0, 0, null,
				profit ? money(rs.getBigDecimal("cost"), rs.getString("currency")) : null, null));
	}

	private static SalesReports.Row row(ResultSet rs, boolean profit) throws SQLException {
		var code = rs.getString("currency");
		var revenue = money(rs.getBigDecimal("revenue"), code);
		var cost = money(rs.getBigDecimal("cost"), code);
		return new SalesReports.Row(rs.getObject("record_id", UUID.class), rs.getString("label"), rs.getString("detail"),
			code, rs.getString("unit"), rs.getBigDecimal("quantity"), rs.getLong("receipts"), rs.getLong("voids"),
			revenue, profit ? cost : null, profit && revenue != null && cost != null ? revenue.subtract(cost) : null,
			rs.getLong("unknown_costs"));
	}

	private static BigDecimal money(BigDecimal value, String currency) {
		return value == null ? null : value.setScale(Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}
}
