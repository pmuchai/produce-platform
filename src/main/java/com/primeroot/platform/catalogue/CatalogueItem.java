package com.primeroot.platform.catalogue;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

@MappedSuperclass
abstract class CatalogueItem {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@Column(nullable = false, unique = true, length = 32)
	private String code;
	@Column(nullable = false, length = 120)
	private String name;
	@Column(nullable = false)
	private boolean active;
	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Version
	private long version;

	void update(String code, String name, boolean active) {
		this.code = code.toUpperCase(Locale.ROOT);
		this.name = name.trim();
		this.active = active;
	}

	public UUID getId() { return id; }
	public String getCode() { return code; }
	public String getName() { return name; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
}
