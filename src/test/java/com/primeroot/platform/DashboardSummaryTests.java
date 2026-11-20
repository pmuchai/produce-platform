package com.primeroot.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.finance.NetProfitReport;
import com.primeroot.platform.inventory.InventoryDtos;
import com.primeroot.platform.ui.DashboardSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardSummaryTests {
	@Test
	void financialOnlySummariesIncludeExactExpensesAndKeepSelectedCurrenciesSeparate() {
		var kes = total("KES", "100", "60", "12.34", "2", "25.66");
		var usd = total("USD", "200", "80", "5", "0", "115");
		var summary = DashboardSummary.build("USD", "KES", List.of(), null, null, report(kes, usd), report());
		assertThat(summary.metrics()).hasSize(6);
		assertThat(summary.metrics().get(0).amount()).isEqualByComparingTo("200");
		assertThat(summary.metrics().get(3).amount()).isEqualByComparingTo("5");
		assertThat(summary.expenses()).isEqualTo("5");
		assertThat(summary.netMargin()).isEqualTo("57.5%");
		assertThat(summary.metrics().stream().filter(metric -> metric.id().equals("stock-losses-card")).findFirst().orElseThrow()
			.amount()).isEqualByComparingTo("0");
		assertThatThrownBy(() -> DashboardSummary.build("NOT_A_CURRENCY", "KES", List.of(), null, null, report(kes, usd), report()))
			.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
	}

	@Test
	void selectedCurrencyRemainsAvailableWhenItHasNoActivityInEitherPeriod() {
		var kes = total("KES", "100", "60", "12.34", "2", "25.66");
		var summary = DashboardSummary.build("USD", "KES", List.of(), null, null, report(kes), report(kes));
		assertThat(summary.currency()).isEqualTo("USD");
		assertThat(summary.currencies()).contains("USD");
		assertThat(summary.metrics().getLast().amount()).isEqualByComparingTo("0");
	}

	@Test
	void unknownCurrencyLossCannotBecomeZeroProfitWhenSelectedCurrencyHasNoCurrentRows() {
		var unknown = new NetProfitReport.Total("UNKNOWN", null, null, null, null, null, null, 1);
		var summary = DashboardSummary.build(null, "KES", List.of(), null, null, report(unknown),
			report(total("KES", "0", "0", "10", "0", "-10")));
		var profit = summary.metrics().getLast();
		assertThat(profit.amount()).isNull();
		assertThat(profit.display()).isEqualTo("Unavailable");
		assertThat(profit.comparison()).isEqualTo("Comparison unavailable");
		assertThat(summary.stockLosses()).isEqualTo("Unknown");
		assertThat(summary.metrics().stream().filter(metric -> metric.id().equals("stock-losses-card")).findFirst().orElseThrow()
			.amount()).isNull();
		assertThat(summary.netMargin()).isEqualTo("--");
	}

	@Test
	void liveStockCombinesBranchesByProductIdentityWithoutMixingProductsOrUnits() {
		var onion = UUID.randomUUID();
		var otherOnion = UUID.randomUUID();
		var stock = DashboardSummary.stock(List.of(balance(onion, "KG", "10"), balance(onion, "KG", "15.123456"),
			balance(otherOnion, "PIECE", "7")));
		assertThat(stock).hasSize(2);
		assertThat(stock.stream().filter(s -> s.productId().equals(onion)).findFirst().orElseThrow().display()).isEqualTo("25.123456");
		assertThat(stock.stream().filter(s -> s.productId().equals(otherOnion)).findFirst().orElseThrow().quantity()).isEqualByComparingTo("7");
	}

	private InventoryDtos.Balance balance(UUID product, String unit, String quantity) {
		return new InventoryDtos.Balance(UUID.randomUUID(), "Branch", product, "Onions", unit, new BigDecimal(quantity),
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, -1, false);
	}

	@Test
	void soldQuantityFiguresKeepUnitsAndSelectedCurrencySeparateWithExactPrecision() {
		var totals = List.of(new com.primeroot.platform.sales.SalesReports.QuantityTotal("KES", "KG", new BigDecimal("12.123456")),
			new com.primeroot.platform.sales.SalesReports.QuantityTotal("KES", "PIECE", new BigDecimal("3")),
			new com.primeroot.platform.sales.SalesReports.QuantityTotal("USD", "KG", new BigDecimal("99")));
		var quantities = DashboardSummary.quantities(totals, "KES");
		assertThat(quantities).hasSize(2);
		assertThat(quantities.getFirst().display()).isEqualTo("12.123456");
		assertThat(quantities.getLast().unit()).isEqualTo("PIECE");
		assertThat(quantities.getLast().quantity()).isEqualByComparingTo("3");
	}

	private NetProfitReport.Total total(String code, String revenue, String cost, String expense, String loss, String net) {
		var sales = new BigDecimal(revenue);
		var cogs = new BigDecimal(cost);
		return new NetProfitReport.Total(code, sales, cogs, sales.subtract(cogs), new BigDecimal(expense),
			new BigDecimal(loss), new BigDecimal(net), 0);
	}

	private NetProfitReport report(NetProfitReport.Total... totals) {
		return new NetProfitReport(List.of(), List.of(totals), 0, 0, List.of());
	}
}
