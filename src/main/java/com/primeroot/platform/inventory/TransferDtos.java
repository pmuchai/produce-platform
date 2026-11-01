package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class TransferDtos {
	private TransferDtos() {}
	public record Dispatch(@NotNull UUID requestId, @NotNull UUID sourceId, @NotNull UUID destinationId,
			@Size(max = 500) String notes, @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines) {}
	public record Line(@NotNull UUID productId,
			@NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal quantity) {}
	public record Receive(@NotNull UUID requestId, @Size(max = 500) String notes,
			@NotEmpty @Size(max = 1000) List<@NotNull @Valid Arrival> allocations) {}
	public record Arrival(@NotNull UUID allocationId,
			@NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal receivedQuantity,
			@NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal shortageQuantity,
			@NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal damagedQuantity,
			@Size(max = 500) String reason) {}
	public record Option(UUID id, String label) {}
	public record Entry(List<Option> sources, List<Option> destinations, List<Option> products) {}
	public record History(List<Summary> transfers, int page, int totalPages, long totalElements) {}
	public record Summary(UUID id, UUID sourceId, String source, UUID destinationId, String destination,
			StockTransfer.Status status, Instant dispatchedAt, Instant receivedAt) {}
	public record Detail(Summary transfer, UUID dispatchedBy, UUID receivedBy, String notes, String receiptNotes,
			boolean canReceive, List<Allocation> allocations) {}
	public record Allocation(UUID id, UUID sourceBatchId, UUID sourceMovementId, UUID destinationBatchId,
			UUID productId, String productCode, String product, String produceType, String variety, String grade,
			String baseUnit, String lotCode, LocalDate sourceReceivedDate, LocalDate expiryDate, String currency,
			UUID sourceInputUnitId, String sourceInputUnit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal purchaseUnitCost,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQuantity,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shortageQuantity,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal damagedQuantity,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedCost,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lossCost,
			String reason) {}
}
