package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class SalesDtos {
	private SalesDtos() {
	}

	public record CreateRequest(@NotNull UUID requestId, @Size(max = 120) String customer,
		@NotNull Sale.PaymentMethod paymentMethod, @Size(max = 120) String paymentReference,
		@Size(max = 500) String notes, @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid LineRequest> lines,
		@JsonInclude(JsonInclude.Include.NON_NULL) LocalDate saleDate) {
	}

	public record LineRequest(@NotNull UUID productId, @NotNull UUID unitId,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal unitPrice,
		@Positive @Digits(integer = 13, fraction = 6) BigDecimal measuredBaseQuantity) {
	}

	public record QuoteRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid LineRequest> lines,
		LocalDate saleDate) {
	}

	public record Quote(String currency, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total) {
	}

	public record VoidRequest(@NotNull UUID requestId, @NotBlank @Size(max = 500) String reason,
		@AssertTrue(message = "Confirm that all goods were returned and the full refund was completed manually")
		boolean returnedAndRefunded, @Size(max = 120) String refundReference) {
	}

	public record Option(UUID id, String label) {
	}

	public record Entry(UUID branchId, String branch, String currency, List<Option> products,
		List<Option> units, List<String> customers) {
	}

	public record Allocation(UUID batchId, String lotCode,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost) {
	}

	public record Line(UUID id, int number, UUID productId, String product, String productCode,
		String unit, String baseUnit, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseQuantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal conversionNumerator,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal conversionDenominator, boolean measured,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost, List<Allocation> allocations) {
	}

	public record Receipt(UUID id, String receiptNumber, UUID branchId, String branch, UUID sellerId,
		String seller, String customer, Instant postedAt, LocalDate saleDate, String currency, Sale.PaymentMethod paymentMethod,
		String paymentReference, String notes, String status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfit,
		Instant voidedAt, UUID voidedBy, String voidReason, String refundReference, List<Line> lines) {
	}
}
