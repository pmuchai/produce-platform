package com.primeroot.platform.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_classifications")
public class ProductClassification extends CatalogueItem {
	public enum Kind { VARIETY, GRADE }

	@ManyToOne(optional = false)
	private ProduceType produceType;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Kind kind;

	void classify(ProduceType produceType, Kind kind) {
		this.produceType = produceType;
		this.kind = kind;
	}

	public ProduceType getProduceType() { return produceType; }
	public Kind getKind() { return kind; }
}
