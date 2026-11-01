package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.util.UUID;

import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.StaffUser;
import com.primeroot.platform.inventory.StockOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sales")
public class Sale implements Persistable<UUID> {
	public enum PaymentMethod { CASH, MPESA, BANK }
	@Id private UUID id;
	@ManyToOne(optional = false) private Branch branch;
	@Column(nullable = false) private UUID sellerId;
	@Column(nullable = false, length = 120) private String sellerName;
	@Column(nullable = false, length = 120) private String branchName;
	@Column(nullable = false, length = 3) private String currency;
	@Column(length = 120) private String customer;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16) private PaymentMethod paymentMethod;
	@Column(length = 120) private String paymentReference;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal total;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal cost;
	@ManyToOne private StockOperation voidOperation;
	@Column(length = 120) private String refundReference;
	@Transient private boolean fresh = true;

	protected Sale() {
	}

	Sale(StockOperation operation, StaffUser actor, SalesDtos.CreateRequest request, String currency,
			BigDecimal total, BigDecimal cost, String reference) {
		id = operation.getId();
		branch = operation.getBranch();
		sellerId = actor.getId();
		sellerName = actor.getFullName();
		branchName = branch.getName();
		this.currency = currency;
		customer = request.customer() == null ? "Walk-ins" : request.customer().trim();
		paymentMethod = request.paymentMethod();
		paymentReference = reference;
		this.total = total;
		this.cost = cost;
	}

	void voidSale(StockOperation operation, String reference) {
		voidOperation = operation;
		refundReference = reference;
	}

	@PostLoad @PostPersist void persisted() { fresh = false; }
	@Override public boolean isNew() { return fresh; }
	@Override public UUID getId() { return id; }
	public Branch getBranch() { return branch; }
	public UUID getSellerId() { return sellerId; }
	public String getSellerName() { return sellerName; }
	public String getBranchName() { return branchName; }
	public String getCurrency() { return currency; }
	public String getCustomer() { return customer; }
	public PaymentMethod getPaymentMethod() { return paymentMethod; }
	public String getPaymentReference() { return paymentReference; }
	public BigDecimal getTotal() { return total; }
	public BigDecimal getCost() { return cost; }
	public StockOperation getVoidOperation() { return voidOperation; }
	public String getRefundReference() { return refundReference; }
}
