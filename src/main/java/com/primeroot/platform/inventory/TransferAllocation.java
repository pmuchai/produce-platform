package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "transfer_allocations")
public class TransferAllocation {
	@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
	@ManyToOne(optional = false) private StockTransfer transfer;
	@ManyToOne(optional = false) private StockMovement movement;
	@ManyToOne private StockBatch destinationBatch;
	@Column(nullable = false, length = 120) private String productName;
	@Column(nullable = false, length = 32) private String productCode;
	@Column(nullable = false, length = 120) private String produceType;
	@Column(length = 120) private String variety;
	@Column(length = 120) private String grade;
	@Column(nullable = false, length = 32) private String baseUnit;
	@Column(nullable = false, precision = 19, scale = 6) private BigDecimal quantity;
	@Column(precision = 38, scale = 6) private BigDecimal cost;
	@Column(precision = 19, scale = 6) private BigDecimal receivedQuantity;
	@Column(precision = 19, scale = 6) private BigDecimal shortageQuantity;
	@Column(precision = 19, scale = 6) private BigDecimal damagedQuantity;
	@Column(precision = 38, scale = 6) private BigDecimal receivedCost;
	@Column(precision = 38, scale = 6) private BigDecimal lossCost;
	@Column(length = 500) private String reason;

	protected TransferAllocation() {}

	TransferAllocation(StockTransfer transfer, StockMovement movement, BigDecimal quantity, BigDecimal cost) {
		this.transfer = transfer;
		this.movement = movement;
		this.quantity = quantity;
		this.cost = cost;
		var batch = movement.getBatch();
		productName = batch.getProductNameSnapshot();
		productCode = batch.getProductCodeSnapshot();
		produceType = batch.getProduceTypeSnapshot();
		variety = batch.getVarietySnapshot();
		grade = batch.getGradeSnapshot();
		baseUnit = batch.getBaseUnitSnapshot();
	}

	void receive(TransferDtos.Arrival item, StockBatch batch, BigDecimal arrivedCost) {
		receivedQuantity = item.receivedQuantity();
		shortageQuantity = item.shortageQuantity();
		damagedQuantity = item.damagedQuantity();
		reason = item.reason() == null ? null : item.reason().trim();
		destinationBatch = batch;
		receivedCost = arrivedCost;
		lossCost = cost == null ? null : cost.subtract(arrivedCost);
	}
	public UUID getId() { return id; }
	public StockMovement getMovement() { return movement; }
	public StockBatch getDestinationBatch() { return destinationBatch; }
	public String getProductName() { return productName; }
	public String getProductCode() { return productCode; }
	public String getProduceType() { return produceType; }
	public String getVariety() { return variety; }
	public String getGrade() { return grade; }
	public String getBaseUnit() { return baseUnit; }
	public BigDecimal getQuantity() { return quantity; }
	public BigDecimal getCost() { return cost; }
	public BigDecimal getReceivedQuantity() { return receivedQuantity; }
	public BigDecimal getShortageQuantity() { return shortageQuantity; }
	public BigDecimal getDamagedQuantity() { return damagedQuantity; }
	public BigDecimal getReceivedCost() { return receivedCost; }
	public BigDecimal getLossCost() { return lossCost; }
	public String getReason() { return reason; }
}
