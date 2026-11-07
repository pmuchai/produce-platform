package com.primeroot.platform.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;

import com.primeroot.platform.sales.SalesReports;

public final class DashboardCharts {
	private DashboardCharts() {
	}

	public record Day(int day, LocalDate date, BigDecimal revenue, BigDecimal previousRevenue) {
	}

	public record CurrencyChart(String currency, BigDecimal revenue, BigDecimal cost, BigDecimal grossProfit,
			long receipts, long voids, BigDecimal change, String percentage, List<Day> days, List<SalesReports.Row> staff) {
		public boolean isPieAvailable() {
			return revenue.signum() > 0 && cost != null && grossProfit != null
				&& cost.signum() >= 0 && grossProfit.signum() >= 0;
		}
	}

	public static List<CurrencyChart> build(DashboardWindow window, SalesReports.Report daily,
			SalesReports.Report previous, SalesReports.Report staff) {
		var currencies = new TreeSet<String>();
		daily.totals().forEach(total -> currencies.add(total.currency()));
		previous.totals().forEach(total -> currencies.add(total.currency()));
		return currencies.stream().map(code -> {
			var total = daily.totals().stream().filter(t -> t.currency().equals(code)).findFirst()
				.orElse(new SalesReports.Summary(code, 0, 0, BigDecimal.ZERO,
					daily.canProfit() ? BigDecimal.ZERO : null, daily.canProfit() ? BigDecimal.ZERO : null));
			var before = previous.totals().stream().filter(t -> t.currency().equals(code))
				.map(SalesReports.Summary::revenue).findFirst().orElse(BigDecimal.ZERO);
			var change = total.revenue().subtract(before);
			String percentage = before.signum() <= 0 ? "No positive prior baseline"
				: change.multiply(BigDecimal.valueOf(100)).divide(before, 1, RoundingMode.HALF_UP) + "%";
			long dayCount = Math.max(ChronoUnit.DAYS.between(window.period().start(), window.period().end()),
				ChronoUnit.DAYS.between(window.previous().start(), window.previous().end())) + 1;
			var days = java.util.stream.IntStream.range(0, (int) dayCount).mapToObj(index -> {
				var date = window.period().start().plusDays(index);
				var priorDate = window.previous().start().plusDays(index);
				return new Day(index + 1, date.isAfter(window.period().end()) ? null : date,
					date.isAfter(window.period().end()) ? null : revenue(daily, code, date),
					priorDate.isAfter(window.previous().end()) ? null : revenue(previous, code, priorDate));
			}).toList();
			return new CurrencyChart(code, total.revenue(), total.cost(), total.grossProfit(), total.receipts(),
				total.voids(), change, percentage, days, staff.rows().stream().filter(row -> row.currency().equals(code)).toList());
		}).toList();
	}

	private static BigDecimal revenue(SalesReports.Report report, String code, LocalDate date) {
		return report.rows().stream().filter(row -> row.currency().equals(code) && row.label().equals(date.toString()))
			.map(SalesReports.Row::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
