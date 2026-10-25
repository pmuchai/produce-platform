package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import com.primeroot.platform.access.Branch;
import com.primeroot.platform.catalogue.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock_thresholds", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "product_id"}))
public class StockThreshold {
	@Id @GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@ManyToOne(optional = false)
	private Branch branch;
	@ManyToOne(optional = false)
	private Product product;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal minimumQuantity;
	@Version
	private long version;

	protected StockThreshold() {
	}

	StockThreshold(Branch branch, Product product) {
		this.branch = branch;
		this.product = product;
	}

	void setMinimumQuantity(BigDecimal quantity) { minimumQuantity = quantity; }
	public UUID getId() { return id; }
	public Branch getBranch() { return branch; }
	public Product getProduct() { return product; }
	public BigDecimal getMinimumQuantity() { return minimumQuantity; }
	public long getVersion() { return version; }
}
