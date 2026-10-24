package com.primeroot.platform.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(updatable = false)
	private UUID actorId;

	@Column(nullable = false, length = 64, updatable = false)
	private String action;

	@Column(nullable = false, length = 32, updatable = false)
	private String targetType;

	@Column(nullable = false, updatable = false)
	private UUID targetId;

	@Column(nullable = false, updatable = false)
	private Instant occurredAt = Instant.now();

	protected AuditEvent() {
	}

	public AuditEvent(UUID actorId, String action, String targetType, UUID targetId) {
		this.actorId = actorId;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
	}

	public UUID getId() { return id; }
	public UUID getActorId() { return actorId; }
	public String getAction() { return action; }
	public String getTargetType() { return targetType; }
	public UUID getTargetId() { return targetId; }
	public Instant getOccurredAt() { return occurredAt; }
}
