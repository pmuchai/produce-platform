package com.primeroot.platform.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;

public record NetProfitReport(List<Row> rows, List<Total> totals, int page, long totalElements,
		List<BranchOption> branches) {
	public record Total(String currency,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costOfGoodsSold,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expenses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stockLosses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit, long unknownCosts) {
	}

	public record Row(UUID branchId, String branch, String branchCode, Total amounts) {
	}
}
