package com.primeroot.platform.inventory;

import java.time.Instant;
import java.util.UUID;

import com.primeroot.platform.access.Branch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "stock_operations")
public class StockOperation implements Persistable<UUID> {
	public enum Kind { RECEIPT, ADJUSTMENT, SPOILAGE, REVERSAL, SALE, SALE_VOID, TRANSFER_OUT, TRANSFER_IN }
	@Id
	private UUID id;
	@ManyToOne(optional = false)
	private Branch branch;
	@Column(nullable = false, updatable = false)
	private UUID actorId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Kind kind;
	@Column(nullable = false, length = 64)
	private String requestHash;
	@Column(length = 120)
	private String source;
	@Column(length = 500)
	private String notes;
	@Column(nullable = false, updatable = false)
	private Instant postedAt = Instant.now();
	@Transient
	private boolean fresh = true;

	protected StockOperation() {
	}

	public StockOperation(UUID id, Branch branch, UUID actorId, Kind kind, String hash, String source, String notes) {
		this.id = id;
		this.branch = branch;
		this.actorId = actorId;
		this.kind = kind;
		this.requestHash = hash;
		this.source = source;
		this.notes = notes;
	}

	@PostLoad
	@PostPersist
	void persisted() { fresh = false; }
	@Override
	public boolean isNew() { return fresh; }
	@Override
	public UUID getId() { return id; }
	public Branch getBranch() { return branch; }
	public UUID getActorId() { return actorId; }
	public Kind getKind() { return kind; }
	public String getRequestHash() { return requestHash; }
	public String getSource() { return source; }
	public String getNotes() { return notes; }
	public Instant getPostedAt() { return postedAt; }
}
