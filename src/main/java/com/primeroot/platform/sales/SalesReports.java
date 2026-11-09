package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

public final class SalesReports {
	private SalesReports() {
	}

	public enum Kind {
		SALES("sales", "Sales and receipts"),
		DAILY("daily-sales", "Daily sales"),
		PRODUCTS("product-sales", "Product sales"),
		BRANCHES("branch-sales", "Branch sales"),
		STAFF("staff-performance", "Staff performance"),
		STAFF_PRODUCTS("staff-product-sales", "Staff Product Sales"),
		CUSTOMERS("customer-sales", "Customer sales"),
		PAYMENTS("payments", "Payments and refunds"),
		PROFIT("profit", "Gross profit and cost of goods sold"),
		VALUATION("stock-valuation", "Current stock valuation"),
		LOSSES("stock-losses", "Stock losses");

		private final String path;
		private final String title;
		Kind(String path, String title) { this.path = path; this.title = title; }
		public String getPath() { return path; }
		public String getTitle() { return title; }
		public boolean isStock() { return this == VALUATION || this == LOSSES; }
		public boolean isFinancial() { return this == PROFIT || this == VALUATION; }
		public static Kind from(String path) {
			return java.util.Arrays.stream(values()).filter(kind -> kind.path.equals(path)).findFirst()
				.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown sales report"));
		}
	}

	public record Summary(String currency, long receipts, long voids,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfit) {
	}

	public record QuantityTotal(String currency, String unit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) {
	}

	public record StaffOption(UUID id, String name, String username) {
	}

	public record StaffProductRow(UUID staffId, String staffName, String username, UUID productId,
		String productName, String productCode, String unit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sold,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refunded,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal net) {
	}

	public record StaffProductTotal(String unit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sold,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refunded,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal net) {
	}

	public record StaffProductReport(Kind kind, List<StaffProductRow> rows, long totalElements, int page,
		List<StaffProductTotal> totals, boolean ownSalesOnly,
		List<com.primeroot.platform.inventory.InventoryDtos.BranchOption> branches, List<StaffOption> staff) {
	}

	public record Row(UUID saleId, String label, String detail, String currency, String unit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, long receipts, long voids,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfit, long unknownCosts) {
	}

	public record Report(Kind kind, List<Row> rows, long totalElements, int page,
		List<Summary> totals, List<Summary> previous, boolean ownSalesOnly, boolean canProfit,
		List<com.primeroot.platform.inventory.InventoryDtos.BranchOption> branches, long sellingStaff, long sellingBranches) {
	}
}
