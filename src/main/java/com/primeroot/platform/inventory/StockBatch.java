package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.access.Branch;
import com.primeroot.platform.catalogue.CatalogueQuantityService.Conversion;
import com.primeroot.platform.catalogue.Product;
import com.primeroot.platform.catalogue.UnitOfMeasure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
	@ManyToOne(optional = false)
	private Branch branch;
	@ManyToOne(optional = false)
	private Product product;
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
	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Version
	private long version;

	protected StockBatch() {
	}

	StockBatch(StockOperation operation, InventoryDtos.ReceiptLine line, LocalDate receivedDate, Conversion conversion) {
		this.operation = operation;
		branch = operation.getBranch();
		product = conversion.product();
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
	}

	void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
	public UUID getId() { return id; }
	public StockOperation getOperation() { return operation; }
	public Branch getBranch() { return branch; }
	public Product getProduct() { return product; }
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
	public Instant getCreatedAt() { return createdAt; }
}
