package com.primeroot.platform.access;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "branches")
public class Branch {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true, length = 32)
	private String code;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 255)
	private String address;

	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Version
	private long version;

	protected Branch() {
	}

	public Branch(String code, String name, String address, boolean active) {
		update(code, name, address, active);
	}

	public void update(String code, String name, String address, boolean active) {
		this.code = code;
		this.name = name.trim();
		this.address = address == null ? null : address.trim();
		this.active = active;
	}

	public UUID getId() { return id; }
	public String getCode() { return code; }
	public String getName() { return name; }
	public String getAddress() { return address; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
	public long getVersion() { return version; }
}
