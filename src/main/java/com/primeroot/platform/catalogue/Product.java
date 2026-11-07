package com.primeroot.platform.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product extends CatalogueItem {
	@ManyToOne(optional = false)
	private ProduceType produceType;
	@ManyToOne
	private ProductClassification variety;
	@ManyToOne(optional = false)
	private UnitOfMeasure baseUnit;

	void define(ProduceType produceType, ProductClassification variety, UnitOfMeasure baseUnit) {
		this.produceType = produceType;
		this.variety = variety;
		this.baseUnit = baseUnit;
	}

	public ProduceType getProduceType() { return produceType; }
	public ProductClassification getVariety() { return variety; }
	public UnitOfMeasure getBaseUnit() { return baseUnit; }
}
