package com.primeroot.platform.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

import com.primeroot.platform.finance.NetProfitReport;
import com.primeroot.platform.inventory.InventoryDtos;
import com.primeroot.platform.sales.SalesReports;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record DashboardSummary(String currency, List<String> currencies, List<Metric> metrics,
		DashboardCharts.CurrencyChart chart, String expenses, String stockLosses, String netMargin) {
	public record Metric(String id, String label, String icon, BigDecimal amount, String display,
			String comparison, String comparisonTitle, boolean increase, boolean favorable) {
	}

	public record Stock(UUID productId, String product, String unit, BigDecimal quantity, String display) {
	}

	public record Quantity(String unit, BigDecimal quantity, String display) {
	}

	public static List<Quantity> quantities(List<SalesReports.QuantityTotal> totals, String currency) {
		return totals.stream().filter(total -> total.currency().equals(currency))
			.map(total -> new Quantity(total.unit(), total.quantity(), quantity(total.quantity()))).toList();
	}

	private static String quantity(BigDecimal value) {
		var format = NumberFormat.getNumberInstance(Locale.ENGLISH);
		format.setMaximumFractionDigits(6);
		return format.format(value);
	}

	public static DashboardSummary build(String requested, String businessCurrency,
			List<DashboardCharts.CurrencyChart> charts, SalesReports.Report sales, SalesReports.Report priorSales,
			NetProfitReport profit, NetProfitReport priorProfit) {
		var codes = new TreeSet<String>();
		codes.add(businessCurrency);
		charts.forEach(chart -> codes.add(chart.currency()));
		if (profit != null) {
			profit.totals().stream().filter(t -> !"UNKNOWN".equals(t.currency())).forEach(t -> codes.add(t.currency()));
			priorProfit.totals().stream().filter(t -> !"UNKNOWN".equals(t.currency())).forEach(t -> codes.add(t.currency()));
		}
		String code = requested == null ? businessCurrency : requested;
		try {
			Currency.getInstance(code);
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a valid currency");
		}
		codes.add(code);
		var chart = charts.stream().filter(c -> c.currency().equals(code)).findFirst().orElse(null);
		var financial = financial(profit, code);
		var priorFinancial = financial(priorProfit, code);
		var currentSales = sales == null ? null : sales.totals().stream().filter(t -> t.currency().equals(code)).findFirst()
			.orElse(new SalesReports.Summary(code, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		var previousSales = priorSales == null ? null : priorSales.totals().stream().filter(t -> t.currency().equals(code)).findFirst()
			.orElse(new SalesReports.Summary(code, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		var revenue = currentSales == null ? financial.revenue() : currentSales.revenue();
		var beforeRevenue = previousSales == null ? priorFinancial.revenue() : previousSales.revenue();
		var cost = currentSales == null ? financial.costOfGoodsSold() : currentSales.cost();
		var beforeCost = previousSales == null ? priorFinancial.costOfGoodsSold() : previousSales.cost();
		var gross = currentSales == null ? financial.grossProfit() : currentSales.grossProfit();
		var beforeGross = previousSales == null ? priorFinancial.grossProfit() : previousSales.grossProfit();
		var metrics = new ArrayList<Metric>();
		if (sales != null || profit != null) metrics.add(metric("sales-card", "Net sales", "trend", revenue, beforeRevenue, code, false));
		if (profit != null) {
			metrics.add(metric("cogs-card", "COGS", "coins", cost, beforeCost, code, true));
			metrics.add(metric("profit-card", "Gross profit", "leaf", gross, beforeGross, code, false));
			metrics.add(metric("expenses-card", "Total expenses", "receipt", financial.expenses(), priorFinancial.expenses(), code, true));
			metrics.add(metric("stock-losses-card", "Spoilage / stock losses", "alert",
				unknownLoss(profit, financial) ? null : financial.stockLosses(),
				unknownLoss(priorProfit, priorFinancial) ? null : priorFinancial.stockLosses(), code, true));
			metrics.add(metric("net-profit-card", "Net profit", "trend", financial.netProfit(), priorFinancial.netProfit(), code, false));
		}
		String margin = financial.netProfit() == null || financial.revenue().signum() <= 0 ? "--"
			: financial.netProfit().multiply(BigDecimal.valueOf(100)).divide(financial.revenue(), 1, RoundingMode.HALF_UP) + "%";
		boolean unknownLoss = financial.unknownCosts() > 0 || profit != null && profit.totals().stream()
			.anyMatch(t -> "UNKNOWN".equals(t.currency()) && t.unknownCosts() > 0);
		return new DashboardSummary(code, List.copyOf(codes), List.copyOf(metrics), chart,
			format(financial.expenses(), code), unknownLoss ? "Unknown" : format(financial.stockLosses(), code), margin);
	}

	private static boolean unknownLoss(NetProfitReport report, NetProfitReport.Total total) {
		return total.unknownCosts() > 0 || report != null && report.totals().stream()
			.anyMatch(t -> "UNKNOWN".equals(t.currency()) && t.unknownCosts() > 0);
	}

	private static NetProfitReport.Total financial(NetProfitReport report, String code) {
		if (report != null) {
			var total = report.totals().stream().filter(t -> t.currency().equals(code)).findFirst();
			if (total.isPresent()) return total.get();
		}
		boolean unknown = report != null && report.totals().stream().anyMatch(t -> "UNKNOWN".equals(t.currency()) && t.unknownCosts() > 0);
		return new NetProfitReport.Total(code, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, unknown ? null : BigDecimal.ZERO, unknown ? 1 : 0);
	}

	private static Metric metric(String id, String label, String icon, BigDecimal current, BigDecimal previous, String code, boolean lowerIsBetter) {
		var delta = current == null || previous == null ? null : current.subtract(previous);
		var comparison = delta == null ? "Comparison unavailable" : previous.signum() > 0
			? (delta.signum() > 0 ? "+" : "") + delta.multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP) + "%"
			: delta.signum() == 0 ? "No change" : "Change " + (delta.signum() > 0 ? "+" : "") + format(delta, code);
		return new Metric(id, label, icon, current, format(current, code), comparison,
			delta == null ? "Unknown costs prevent comparison" : code + " " + delta.toPlainString() + " vs comparison period",
			delta != null && delta.signum() >= 0, delta != null && (lowerIsBetter ? delta.signum() <= 0 : delta.signum() >= 0));
	}

	private static String format(BigDecimal value, String code) {
		if (value == null) return "Unavailable";
		var format = NumberFormat.getNumberInstance(Locale.ENGLISH);
		int scale = Currency.getInstance(code).getDefaultFractionDigits();
		format.setMinimumFractionDigits(value.stripTrailingZeros().scale() <= 0 ? 0 : scale);
		format.setMaximumFractionDigits(scale);
		return format.format(value);
	}

	public static List<Stock> stock(List<InventoryDtos.Balance> balances) {
		record Key(UUID product, String unit) {}
		var totals = new LinkedHashMap<Key, BigDecimal>();
		var names = new LinkedHashMap<Key, String>();
		for (var balance : balances) {
			var key = new Key(balance.productId(), balance.baseUnit());
			totals.merge(key, balance.current(), BigDecimal::add);
			names.put(key, balance.product());
		}
		return totals.entrySet().stream().map(entry -> {
			return new Stock(entry.getKey().product(), names.get(entry.getKey()), entry.getKey().unit(),
				entry.getValue(), quantity(entry.getValue()));
		}).sorted(java.util.Comparator.comparing(Stock::product).thenComparing(Stock::productId)).toList();
	}
}
