package com.primeroot.platform.catalogue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CatalogueQuantityService {
	private final ProductRepository products;
	private final UnitRepository units;
	private final PackagingRepository packaging;

	public CatalogueQuantityService(ProductRepository products, UnitRepository units, PackagingRepository packaging) {
		this.products = products;
		this.units = units;
		this.packaging = packaging;
	}

	public Conversion resolve(UUID productId, UUID unitId, BigDecimal quantity, BigDecimal measuredBaseQuantity) {
		var product = products.findById(productId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
		var unit = units.findById(unitId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unit not found"));
		var base = product.getBaseUnit();
		for (var item : new CatalogueItem[] {product, product.getProduceType(), product.getVariety(),
				product.getGrade(), base, unit}) {
			if (item != null && !item.isActive()) {
				throw invalid("Receiving and conversions require active catalogue definitions");
			}
		}
		BigDecimal numerator;
		BigDecimal denominator;
		BigDecimal result;
		if (measuredBaseQuantity != null) {
			if (unit.getDimension() != UnitOfMeasure.Dimension.PACKAGING) {
				throw invalid("Actual measured base quantity is only supported for packaging");
			}
			numerator = null;
			denominator = null;
			result = measuredBaseQuantity;
		}
		else {
			if (unit.getDimension() == UnitOfMeasure.Dimension.PACKAGING) {
				numerator = packaging.findByProductIdAndUnitId(productId, unitId)
					.orElseThrow(() -> invalid("Define fixed packaging or enter the actual measured base quantity"))
					.getQuantityInBaseUnit();
				denominator = BigDecimal.ONE;
			}
			else {
				if (unit.getDimension() != base.getDimension()) {
					throw invalid("Cannot convert between incompatible measurement dimensions");
				}
				numerator = unit.getBaseFactor();
				denominator = base.getBaseFactor();
			}
			result = quantity.multiply(numerator).divide(denominator, 6, RoundingMode.HALF_UP);
		}
		result = result.setScale(6, RoundingMode.HALF_UP);
		if (result.signum() <= 0 || result.compareTo(new BigDecimal("9999999999999.999999")) > 0) {
			throw invalid("Converted quantity must be positive and fit 13 integer and six fractional digits");
		}
		return new Conversion(product, unit, result, numerator, denominator, measuredBaseQuantity != null);
	}

	public record Conversion(Product product, UnitOfMeasure unit, BigDecimal quantity,
		BigDecimal numerator, BigDecimal denominator, boolean measured) {
	}

	private static ResponseStatusException invalid(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}
}
