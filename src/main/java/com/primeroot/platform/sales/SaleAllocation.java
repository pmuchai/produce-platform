package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.util.UUID;

import com.primeroot.platform.inventory.SaleStockLedger.Allocation;
import com.primeroot.platform.inventory.StockMovement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_allocations")
public class SaleAllocation {
	@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
	@ManyToOne(optional = false) private SaleLine line;
	@ManyToOne(optional = false) private StockMovement movement;
	@Column(nullable = false, precision = 19, scale = 6) private BigDecimal quantity;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal cost;

	protected SaleAllocation() {
	}

	SaleAllocation(SaleLine line, Allocation allocation) {
		this.line = line;
		movement = allocation.movement();
		quantity = allocation.quantity();
		cost = allocation.cost();
	}

	public SaleLine getLine() { return line; }
	public StockMovement getMovement() { return movement; }
	public BigDecimal getQuantity() { return quantity; }
	public BigDecimal getCost() { return cost; }
}
