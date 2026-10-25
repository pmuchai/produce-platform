package com.primeroot.platform.catalogue;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "product_packaging")
public class ProductPackaging {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@ManyToOne(optional = false)
	private Product product;
	@ManyToOne(optional = false)
	private UnitOfMeasure unit;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal quantityInBaseUnit;
	@Version
	private long version;

	protected ProductPackaging() {
	}

	ProductPackaging(Product product, UnitOfMeasure unit) {
		this.product = product;
		this.unit = unit;
	}

	void update(BigDecimal quantity) { quantityInBaseUnit = quantity; }
	public UUID getId() { return id; }
	public Product getProduct() { return product; }
	public UnitOfMeasure getUnit() { return unit; }
	public BigDecimal getQuantityInBaseUnit() { return quantityInBaseUnit; }
	public long getVersion() { return version; }
}
