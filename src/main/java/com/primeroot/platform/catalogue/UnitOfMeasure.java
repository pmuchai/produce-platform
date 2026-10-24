package com.primeroot.platform.catalogue;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "units_of_measure")
public class UnitOfMeasure extends CatalogueItem {
	public enum Dimension { MASS, COUNT, PACKAGING }

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Dimension dimension;
	@Column(precision = 19, scale = 6)
	private BigDecimal baseFactor;

	void measure(Dimension dimension, BigDecimal baseFactor) {
		this.dimension = dimension;
		this.baseFactor = baseFactor;
	}

	public Dimension getDimension() { return dimension; }
	public BigDecimal getBaseFactor() { return baseFactor; }
}
