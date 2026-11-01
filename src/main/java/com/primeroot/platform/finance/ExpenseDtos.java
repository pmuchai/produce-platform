package com.primeroot.platform.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;
import com.primeroot.platform.sales.Sale;
import jakarta.validation.constraints.*;

public final class ExpenseDtos {
	private ExpenseDtos() {
	}

	public record Create(@NotNull UUID requestId, @NotNull UUID branchId,
		@NotNull @PastOrPresent LocalDate paidDate, @NotNull Expense.Category category,
		@NotBlank @Size(max = 500) String description, @Size(max = 120) String payee,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal amount,
		@NotNull Sale.PaymentMethod paymentMethod, @Size(max = 120) String paymentReference) {
	}

	public record Void(@NotNull UUID requestId, @NotBlank @Size(max = 500) String reason) {
	}

	public record Entry(String currency, int fractionDigits, String amountStep, List<BranchOption> branches,
		List<Expense.Category> categories) {
	}

	public record Detail(UUID id, UUID branchId, String branch, UUID actorId, String actor, Instant postedAt,
		LocalDate paidDate, Expense.Category category, String description, String payee, String currency,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, Sale.PaymentMethod paymentMethod,
		String paymentReference, Instant voidedAt, UUID voidedBy, String voidReason, boolean canVoid) {
	}

	public record History(List<Detail> rows, int page, long totalElements, List<BranchOption> branches) {
	}
}
