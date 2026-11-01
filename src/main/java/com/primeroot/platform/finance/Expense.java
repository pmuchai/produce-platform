package com.primeroot.platform.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.StaffUser;
import com.primeroot.platform.sales.Sale;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "expenses")
public class Expense implements Persistable<UUID> {
	public enum Category { RENT, UTILITIES, TRANSPORT, WAGES, MAINTENANCE, OTHER }
	@Id private UUID id;
	@ManyToOne(optional = false) private Branch branch;
	@Column(nullable = false, length = 120) private String branchName;
	@Column(nullable = false) private UUID actorId;
	@Column(nullable = false, length = 120) private String actorName;
	@Column(nullable = false) private Instant postedAt;
	@Column(nullable = false) private LocalDate paidDate;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Category category;
	@Column(nullable = false, length = 500) private String description;
	@Column(length = 120) private String payee;
	@Column(nullable = false, length = 3) private String currency;
	@Column(nullable = false, precision = 38, scale = 6) private BigDecimal amount;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Sale.PaymentMethod paymentMethod;
	@Column(length = 120) private String paymentReference;
	@Column(nullable = false, length = 64) private String requestHash;
	private UUID voidRequestId;
	@Column(length = 64) private String voidHash;
	private Instant voidedAt;
	private UUID voidedBy;
	@Column(length = 500) private String voidReason;
	@Version private long version;
	@Transient private boolean fresh = true;

	protected Expense() {
	}

	Expense(Branch branch, StaffUser actor, ExpenseDtos.Create request, String currency, BigDecimal amount,
			String reference, String hash) {
		id = request.requestId();
		this.branch = branch;
		branchName = branch.getName();
		actorId = actor.getId();
		actorName = actor.getFullName();
		postedAt = Instant.now();
		paidDate = request.paidDate();
		category = request.category();
		description = request.description().trim();
		payee = request.payee() == null || request.payee().isBlank() ? null : request.payee().trim();
		this.currency = currency;
		this.amount = amount;
		paymentMethod = request.paymentMethod();
		paymentReference = reference;
		requestHash = hash;
	}

	void voidExpense(UUID actor, ExpenseDtos.Void request, String hash) {
		voidRequestId = request.requestId();
		voidHash = hash;
		voidedAt = Instant.now();
		voidedBy = actor;
		voidReason = request.reason().trim();
	}

	@PostLoad @PostPersist void persisted() { fresh = false; }
	@Override public boolean isNew() { return fresh; }
	@Override public UUID getId() { return id; }
	public Branch getBranch() { return branch; }
	public String getBranchName() { return branchName; }
	public UUID getActorId() { return actorId; }
	public String getActorName() { return actorName; }
	public Instant getPostedAt() { return postedAt; }
	public LocalDate getPaidDate() { return paidDate; }
	public Category getCategory() { return category; }
	public String getDescription() { return description; }
	public String getPayee() { return payee; }
	public String getCurrency() { return currency; }
	public BigDecimal getAmount() { return amount; }
	public Sale.PaymentMethod getPaymentMethod() { return paymentMethod; }
	public String getPaymentReference() { return paymentReference; }
	public String getRequestHash() { return requestHash; }
	public UUID getVoidRequestId() { return voidRequestId; }
	public String getVoidHash() { return voidHash; }
	public Instant getVoidedAt() { return voidedAt; }
	public UUID getVoidedBy() { return voidedBy; }
	public String getVoidReason() { return voidReason; }
}
