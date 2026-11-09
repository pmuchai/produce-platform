package com.primeroot.platform.sales;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.access.StaffUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sale_date_corrections")
public class SaleDateCorrection implements Persistable<UUID> {
	@Id private UUID id;
	@ManyToOne(optional = false) private Sale sale;
	@Column(nullable = false, updatable = false) private UUID actorId;
	@Column(nullable = false, length = 120, updatable = false) private String actorName;
	@Column(nullable = false, updatable = false) private LocalDate previousDate;
	@Column(nullable = false, updatable = false) private LocalDate correctedDate;
	@Column(nullable = false, length = 500, updatable = false) private String reason;
	@Column(nullable = false, length = 64, updatable = false) private String requestHash;
	@Column(nullable = false, updatable = false) private Instant correctedAt = Instant.now();
	@Transient private boolean fresh = true;

	protected SaleDateCorrection() {
	}

	SaleDateCorrection(Sale sale, StaffUser actor, SalesDtos.DateCorrectionRequest request, String hash) {
		id = request.requestId();
		this.sale = sale;
		actorId = actor.getId();
		actorName = actor.getFullName();
		previousDate = sale.getSaleDate();
		correctedDate = request.saleDate();
		reason = request.reason().trim();
		requestHash = hash;
	}

	@PostLoad @PostPersist void persisted() { fresh = false; }
	@Override public boolean isNew() { return fresh; }
	@Override public UUID getId() { return id; }
	public LocalDate getPreviousDate() { return previousDate; }
	public LocalDate getCorrectedDate() { return correctedDate; }
	public String getActorName() { return actorName; }
	public String getReason() { return reason; }
	public String getRequestHash() { return requestHash; }
	public Instant getCorrectedAt() { return correctedAt; }
}
