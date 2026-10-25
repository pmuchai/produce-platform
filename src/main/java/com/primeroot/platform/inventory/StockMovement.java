package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock_movements")
public class StockMovement {
	@Id @GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@ManyToOne(optional = false)
	private StockOperation operation;
	@ManyToOne(optional = false)
	private StockBatch batch;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private StockOperation.Kind kind;
	@Column(nullable = false, precision = 19, scale = 6)
	private BigDecimal quantity;
	@Column(precision = 38, scale = 6)
	private BigDecimal costChange;
	@ManyToOne
	@JoinColumn(name = "reversal_of", unique = true)
	private StockMovement reversalOf;

	protected StockMovement() {
	}

	StockMovement(StockOperation operation, StockBatch batch, StockOperation.Kind kind,
			BigDecimal quantity, StockMovement reversalOf) {
		this.operation = operation;
		this.batch = batch;
		this.kind = kind;
		this.quantity = quantity;
		this.reversalOf = reversalOf;
		costChange = kind == StockOperation.Kind.RECEIPT ? batch.getReceivedCost() : null;
	}

	void setCostChange(BigDecimal cost) { costChange = cost; }
	public BigDecimal getCostChange() { return costChange; }
	public UUID getId() { return id; }
	public StockOperation getOperation() { return operation; }
	public StockBatch getBatch() { return batch; }
	public StockOperation.Kind getKind() { return kind; }
	public BigDecimal getQuantity() { return quantity; }
	public StockMovement getReversalOf() { return reversalOf; }
}
