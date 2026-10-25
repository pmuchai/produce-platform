package com.primeroot.platform.catalogue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CatalogueDtos {
	private CatalogueDtos() {
	}

	public record TypeRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
		@NotBlank @Size(max = 120) String name, @NotNull Boolean active) {
	}

	public record ClassificationRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
		@NotBlank @Size(max = 120) String name, @NotNull Boolean active,
		@NotNull UUID produceTypeId, @NotNull ProductClassification.Kind kind) {
	}

	public record UnitRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
		@NotBlank @Size(max = 120) String name, @NotNull Boolean active,
		@NotNull UnitOfMeasure.Dimension dimension,
		@Positive @Digits(integer = 13, fraction = 6) BigDecimal baseFactor) {
	}

	public record ProductRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
		@NotBlank @Size(max = 120) String name, @NotNull Boolean active,
		@NotNull UUID produceTypeId, UUID varietyId, UUID gradeId, @NotNull UUID baseUnitId) {
	}

	public record PackagingRequest(@NotNull UUID productId, @NotNull UUID unitId,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantityInBaseUnit) {
	}

	public record ConvertRequest(@NotNull UUID productId, @NotNull UUID fromUnitId,
		@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity) {
	}

	public record TypeResponse(UUID id, String code, String name, boolean active, long version) {
		static TypeResponse from(ProduceType item) {
			return new TypeResponse(item.getId(), item.getCode(), item.getName(), item.isActive(), item.getVersion());
		}
	}

	public record ClassificationResponse(UUID id, String code, String name, boolean active,
			UUID produceTypeId, ProductClassification.Kind kind, long version) {
		static ClassificationResponse from(ProductClassification item) {
			return new ClassificationResponse(item.getId(), item.getCode(), item.getName(), item.isActive(),
				item.getProduceType().getId(), item.getKind(), item.getVersion());
		}
	}

	public record UnitResponse(UUID id, String code, String name, boolean active,
			UnitOfMeasure.Dimension dimension, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseFactor,
			long version) {
		static UnitResponse from(UnitOfMeasure item) {
			return new UnitResponse(item.getId(), item.getCode(), item.getName(), item.isActive(),
				item.getDimension(), item.getBaseFactor(), item.getVersion());
		}
	}

	public record ProductResponse(UUID id, String code, String name, boolean active,
			UUID produceTypeId, UUID varietyId, UUID gradeId, UUID baseUnitId, long version) {
		static ProductResponse from(Product item) {
			return new ProductResponse(item.getId(), item.getCode(), item.getName(), item.isActive(),
				item.getProduceType().getId(), item.getVariety() == null ? null : item.getVariety().getId(),
				item.getGrade() == null ? null : item.getGrade().getId(), item.getBaseUnit().getId(),
				item.getVersion());
		}
	}

	public record PackagingResponse(UUID id, UUID productId, UUID unitId,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantityInBaseUnit, long version) {
		static PackagingResponse from(ProductPackaging item) {
			return new PackagingResponse(item.getId(), item.getProduct().getId(), item.getUnit().getId(),
				item.getQuantityInBaseUnit(), item.getVersion());
		}
	}

	public record ConversionResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, String unitCode) {
	}

	public record Snapshot(List<TypeResponse> types, List<ClassificationResponse> classifications,
		List<UnitResponse> units, List<ProductResponse> products, List<PackagingResponse> packaging) {
	}
}
