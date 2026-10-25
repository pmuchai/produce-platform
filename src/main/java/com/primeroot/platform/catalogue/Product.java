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
	@ManyToOne
	private ProductClassification grade;
	@ManyToOne(optional = false)
	private UnitOfMeasure baseUnit;

	void define(ProduceType produceType, ProductClassification variety,
			ProductClassification grade, UnitOfMeasure baseUnit) {
		this.produceType = produceType;
		this.variety = variety;
		this.grade = grade;
		this.baseUnit = baseUnit;
	}

	public ProduceType getProduceType() { return produceType; }
	public ProductClassification getVariety() { return variety; }
	public ProductClassification getGrade() { return grade; }
	public UnitOfMeasure getBaseUnit() { return baseUnit; }
}
