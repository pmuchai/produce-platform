package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.util.UUID;

import com.primeroot.platform.catalogue.CatalogueQuantityService.Conversion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_lines")
public class SaleLine {
	@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
	@ManyToOne(optional = false) private Sale sale;
	@Column(nullable = false) private int lineNumber;
	@Column(nullable = false) private UUID productId;
	@Column(nullable = false, length = 120) private String productName;
	@Column(nullable = false, length = 32) private String productCode;
	@Column(nullable = false, length = 32) private String inputUnit;
	@Column(nullable = false, length = 32) private String baseUnit;
	@Column(nullable = false, precision = 19, scale = 6) private BigDecimal quantity;
	@Column(nullable = false, precision = 19, scale = 6) private BigDecimal baseQuantity;
	@Column(precision = 19, scale = 6) private BigDecimal conversionNumerator;
	@Column(precision = 19, scale = 6) private BigDecimal conversionDenominator;
	@Column(nullable = false) private boolean measured;
	@Column(nullable = false, precision = 19, scale = 6) private BigDecimal unitPrice;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal total;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal cost;

	protected SaleLine() {
	}

	SaleLine(Sale sale, int lineNumber, SalesDtos.LineRequest request, Conversion conversion,
			BigDecimal total, BigDecimal cost) {
		this.sale = sale;
		this.lineNumber = lineNumber;
		productId = conversion.product().getId();
		productName = conversion.product().getName();
		productCode = conversion.product().getCode();
		inputUnit = conversion.unit().getCode();
		baseUnit = conversion.product().getBaseUnit().getCode();
		quantity = request.quantity();
		baseQuantity = conversion.quantity();
		conversionNumerator = conversion.numerator();
		conversionDenominator = conversion.denominator();
		measured = conversion.measured();
		unitPrice = request.unitPrice();
		this.total = total;
		this.cost = cost;
	}

	public UUID getId() { return id; }
	public int getLineNumber() { return lineNumber; }
	public UUID getProductId() { return productId; }
	public String getProductName() { return productName; }
	public String getProductCode() { return productCode; }
	public String getInputUnit() { return inputUnit; }
	public String getBaseUnit() { return baseUnit; }
	public BigDecimal getQuantity() { return quantity; }
	public BigDecimal getBaseQuantity() { return baseQuantity; }
	public BigDecimal getConversionNumerator() { return conversionNumerator; }
	public BigDecimal getConversionDenominator() { return conversionDenominator; }
	public boolean isMeasured() { return measured; }
	public BigDecimal getUnitPrice() { return unitPrice; }
	public BigDecimal getTotal() { return total; }
	public BigDecimal getCost() { return cost; }
}
