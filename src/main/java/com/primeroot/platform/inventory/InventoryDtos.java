package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {
	private InventoryDtos() {
	}

	public record ReceiptRequest(@NotNull UUID requestId, @NotNull UUID branchId,
		@NotNull LocalDate receivedDate, @NotBlank @Size(max = 120) String source,
		@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
		@Size(max = 500) String notes, @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid ReceiptLine> lines) {
	}

	public record ReceiptLine(@NotNull UUID productId, @NotNull UUID unitId,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
		@NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal purchaseUnitCost,
		@Positive @Digits(integer = 13, fraction = 6) BigDecimal measuredBaseQuantity,
		@NotBlank @Size(max = 64) String lotCode, LocalDate expiryDate,
		@JsonInclude(JsonInclude.Include.NON_NULL) UUID gradeId) {
	}

	public record ChangeRequest(@NotNull UUID requestId, @NotNull UUID branchId, @NotNull UUID batchId,
		@NotNull @Digits(integer = 13, fraction = 6) BigDecimal quantity,
		@NotBlank @Size(max = 500) String reason) {
	}

	public record ReverseRequest(@NotNull UUID requestId, @NotNull UUID branchId,
		@NotNull UUID movementId, @NotBlank @Size(max = 500) String reason) {
	}

	public record ThresholdRequest(@NotNull UUID branchId, @NotNull UUID productId,
		@NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal minimumQuantity,
		@NotNull @Min(-1) Long expectedVersion) {
	}

	public record BranchOption(UUID id, String name, boolean active) {
	}

	public record ProductOption(UUID id, String code, String name, String baseUnit, boolean active, UUID produceTypeId,
		UUID baseUnitId, String produceType) {
	}

	public record GradeOption(UUID id, String name, UUID produceTypeId) {
	}

	public record UnitOption(UUID id, String code) {
	}

	public record BatchResponse(UUID id, UUID branchId, String branch, UUID productId, String product,
		String baseUnit, String lotCode, LocalDate receivedDate, LocalDate expiryDate,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		String inputUnit, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputQuantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQuantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal conversionNumerator,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal conversionDenominator,
		boolean measured, String source, boolean nearExpiry, String currency,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal purchaseUnitCost,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedCost,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseUnitCost, UUID gradeId, String grade) {
		static BatchResponse from(StockBatch batch, LocalDate today) {
			return new BatchResponse(batch.getId(), batch.getBranch().getId(), batch.getBranch().getName(),
				batch.getProduct().getId(), batch.getProduct().getName(), batch.getProduct().getBaseUnit().getCode(),
				batch.getLotCode(), batch.getReceivedDate(), batch.getExpiryDate(), batch.getQuantity(),
				batch.getInputUnit().getCode(), batch.getInputQuantity(), batch.getReceivedQuantity(),
				batch.getConversionNumerator(), batch.getConversionDenominator(), batch.isMeasured(),
				batch.getOperation().getSource(), batch.getQuantity().signum() > 0 && batch.getExpiryDate() != null
					&& !batch.getExpiryDate().isAfter(today.plusDays(7)),
				batch.getCurrency(), batch.getPurchaseUnitCost(), batch.getReceivedCost(), batch.getBaseUnitCost(),
				batch.getGrade() == null ? null : batch.getGrade().getId(), batch.getGradeSnapshot());
		}
	}

	public record MovementResponse(UUID id, UUID operationId, UUID branchId, String branch,
		UUID batchId, String product, String baseUnit, String lotCode, StockOperation.Kind kind,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, Instant postedAt,
		UUID actorId, String source, String notes, UUID reversalOf, boolean reversed, String grade) {
		static MovementResponse from(StockMovement movement, boolean reversed) {
			var batch = movement.getBatch();
			var operation = movement.getOperation();
			return new MovementResponse(movement.getId(), operation.getId(), batch.getBranch().getId(),
				batch.getBranch().getName(), batch.getId(), batch.getProduct().getName(),
				batch.getProduct().getBaseUnit().getCode(), batch.getLotCode(), movement.getKind(),
				movement.getQuantity(), operation.getPostedAt(), operation.getActorId(), operation.getSource(),
				operation.getNotes(), movement.getReversalOf() == null ? null : movement.getReversalOf().getId(), reversed,
				batch.getGradeSnapshot());
		}
	}

	public record OperationResponse(UUID id, List<MovementResponse> movements) {
	}

	public record ThresholdResponse(UUID branchId, UUID productId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimumQuantity, long version) {
		static ThresholdResponse from(StockThreshold threshold) {
			return new ThresholdResponse(threshold.getBranch().getId(), threshold.getProduct().getId(),
				threshold.getMinimumQuantity(), threshold.getVersion());
		}
	}

	public record Balance(UUID branchId, String branch, UUID productId, String product, String baseUnit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal current,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal asOf,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receipts,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal previousReceipts,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal spoilage,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal previousSpoilage,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimum, long thresholdVersion, boolean lowStock) {
	}

	public record BatchPage(List<BatchResponse> batches, long totalElements, int page, String search) {
	}

	public record Snapshot(List<BranchOption> branches, List<ProductOption> products, List<UnitOption> units,
		List<Balance> balances, List<MovementResponse> movements,
		long movementCount, int page, long lowStockCount, long nearExpiryCount, String currency, List<GradeOption> grades) {
	}
}
