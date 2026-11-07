package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Currency;

import com.primeroot.platform.access.Branch;
import com.primeroot.platform.catalogue.CatalogueQuantityService.Conversion;
import com.primeroot.platform.catalogue.Product;
import com.primeroot.platform.catalogue.ProductClassification;
import com.primeroot.platform.catalogue.UnitOfMeasure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock_batches")
public class StockBatch {
	@Id @GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@ManyToOne(optional = false)
	private StockOperation operation;
	@ManyToOne(fetch = FetchType.LAZY)
	private StockBatch sourceBatch;
	@ManyToOne(optional = false)
	private Branch branch;
	@ManyToOne(optional = false)
	private Product product;
	@ManyToOne
	@jakarta.persistence.JoinColumn(updatable = false)
	private ProductClassification grade;
	@Column(length = 120, updatable = false)
	private String productNameSnapshot;
	@Column(length = 32, updatable = false)
	private String productCodeSnapshot;
	@Column(length = 120, updatable = false)
	private String produceTypeSnapshot;
	@Column(length = 120, updatable = false)
	private String varietySnapshot;
	@Column(length = 120, updatable = false)
	private String gradeSnapshot;
	@Column(length = 32, updatable = false)
	private String baseUnitSnapshot;
	@Column(nullable = false, length = 64)
	private String lotCode;
	@Column(nullable = false)
	private LocalDate receivedDate;
	private LocalDate expiryDate;
	@ManyToOne(optional = false)
	private UnitOfMeasure inputUnit;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal inputQuantity;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal receivedQuantity;
	@Column(precision = 19, scale = 6)
	private BigDecimal conversionNumerator;
	@Column(precision = 19, scale = 6)
	private BigDecimal conversionDenominator;
	@Column(nullable = false)
	private boolean measured;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal quantity;
	@Column(length = 3, updatable = false)
	private String currency;
	@Column(precision = 38, scale = 6, updatable = false)
	private BigDecimal purchaseUnitCost;
	@Column(precision = 38, scale = 6, updatable = false)
	private BigDecimal receivedCost;
	@Column(precision = 38, scale = 6)
	private BigDecimal remainingCost;
	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Version
	private long version;

	protected StockBatch() {
	}

	StockBatch(StockOperation operation, StockBatch source, BigDecimal received, BigDecimal cost) {
		this.operation = operation;
		sourceBatch = source;
		branch = operation.getBranch();
		product = source.product;
		grade = source.grade;
		productNameSnapshot = source.getProductNameSnapshot();
		productCodeSnapshot = source.getProductCodeSnapshot();
		produceTypeSnapshot = source.getProduceTypeSnapshot();
		varietySnapshot = source.getVarietySnapshot();
		gradeSnapshot = source.getGradeSnapshot();
		baseUnitSnapshot = source.getBaseUnitSnapshot();
		lotCode = source.lotCode;
		receivedDate = source.receivedDate;
		expiryDate = source.expiryDate;
		// Arrival is a new base-unit batch, not a new purchase or packaging conversion.
		inputUnit = product.getBaseUnit();
		inputQuantity = received;
		receivedQuantity = received;
		quantity = received;
		conversionNumerator = BigDecimal.ONE;
		conversionDenominator = BigDecimal.ONE;
		measured = false;
		currency = cost == null ? null : source.currency;
		purchaseUnitCost = cost == null ? null : cost.divide(received, 6, RoundingMode.HALF_UP);
		receivedCost = cost;
		remainingCost = cost;
	}

	StockBatch(StockOperation operation, InventoryDtos.ReceiptLine line, LocalDate receivedDate,
			Conversion conversion, Currency currency, ProductClassification grade) {
		this.operation = operation;
		branch = operation.getBranch();
		product = conversion.product();
		productNameSnapshot = product.getName();
		productCodeSnapshot = product.getCode();
		produceTypeSnapshot = product.getProduceType().getName();
		varietySnapshot = product.getVariety() == null ? null : product.getVariety().getName();
		this.grade = grade;
		gradeSnapshot = grade == null ? null : grade.getName();
		baseUnitSnapshot = product.getBaseUnit().getCode();
		lotCode = line.lotCode().trim();
		this.receivedDate = receivedDate;
		expiryDate = line.expiryDate();
		inputUnit = conversion.unit();
		inputQuantity = line.quantity();
		receivedQuantity = conversion.quantity();
		quantity = receivedQuantity;
		conversionNumerator = conversion.numerator();
		conversionDenominator = conversion.denominator();
		measured = conversion.measured();
		this.currency = currency.getCurrencyCode();
		purchaseUnitCost = line.purchaseUnitCost();
		receivedCost = inputQuantity.multiply(purchaseUnitCost)
			.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
		remainingCost = receivedCost;
	}

	void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
	void setRemainingCost(BigDecimal cost) { remainingCost = cost; }
	public BigDecimal getRemainingCost() {
		return receivedCost == null || remainingCost == null ? null
			: remainingCost.setScale(Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}
	BigDecimal proportionalCost(BigDecimal quantity) {
		return receivedCost == null ? null : receivedCost.multiply(quantity)
			.divide(receivedQuantity, Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}
	public UUID getId() { return id; }
	public StockOperation getOperation() { return operation; }
	public StockBatch getSourceBatch() { return sourceBatch; }
	public Branch getBranch() { return branch; }
	public Product getProduct() { return product; }
	public ProductClassification getGrade() { return grade; }
	public String getProductNameSnapshot() { return productNameSnapshot == null ? product.getName() : productNameSnapshot; }
	public String getProductCodeSnapshot() { return productCodeSnapshot == null ? product.getCode() : productCodeSnapshot; }
	public String getProduceTypeSnapshot() { return produceTypeSnapshot == null ? product.getProduceType().getName() : produceTypeSnapshot; }
	public String getVarietySnapshot() { return varietySnapshot; }
	public String getGradeSnapshot() { return gradeSnapshot; }
	public String getBaseUnitSnapshot() { return baseUnitSnapshot == null ? product.getBaseUnit().getCode() : baseUnitSnapshot; }
	public String getLotCode() { return lotCode; }
	public LocalDate getReceivedDate() { return receivedDate; }
	public LocalDate getExpiryDate() { return expiryDate; }
	public UnitOfMeasure getInputUnit() { return inputUnit; }
	public BigDecimal getInputQuantity() { return inputQuantity; }
	public BigDecimal getReceivedQuantity() { return receivedQuantity; }
	public BigDecimal getConversionNumerator() { return conversionNumerator; }
	public BigDecimal getConversionDenominator() { return conversionDenominator; }
	public boolean isMeasured() { return measured; }
	public BigDecimal getQuantity() { return quantity; }
	public String getCurrency() { return currency; }
	public BigDecimal getPurchaseUnitCost() { return purchaseUnitCost; }
	public BigDecimal getReceivedCost() {
		return receivedCost == null ? null : receivedCost.setScale(Currency.getInstance(currency).getDefaultFractionDigits());
	}
	public BigDecimal getBaseUnitCost() {
		return receivedCost == null ? null : receivedCost.divide(receivedQuantity, 12, RoundingMode.HALF_UP);
	}
	public Instant getCreatedAt() { return createdAt; }
}
