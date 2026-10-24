package com.primeroot.platform.access;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "staff_users")
public class StaffUser {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true, length = 64, updatable = false)
	private String username;

	@Column(nullable = false, length = 120)
	private String fullName;

	@Column(nullable = false, length = 1024, updatable = false)
	private String issuer;

	@Column(nullable = false, length = 255, updatable = false)
	private String subject;

	@Column(nullable = false)
	private boolean allBranches;

	@Column(nullable = false)
	private boolean active = true;

	@Column(nullable = false)
	private long securityVersion;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Version
	private long version;

	@ManyToMany
	@JoinTable(name = "user_branches",
		joinColumns = @JoinColumn(name = "user_id"),
		inverseJoinColumns = @JoinColumn(name = "branch_id"))
	private Set<Branch> branches = new HashSet<>();

	protected StaffUser() {
	}

	public StaffUser(String username, String fullName, String issuer, String subject,
			boolean allBranches, Set<Branch> branches) {
		this.username = username;
		this.fullName = fullName.trim();
		this.issuer = issuer;
		this.subject = subject;
		this.allBranches = allBranches;
		this.branches.addAll(branches);
	}

	public void update(String fullName, boolean active, boolean allBranches, Set<Branch> branches) {
		this.fullName = fullName.trim();
		this.active = active;
		this.allBranches = allBranches;
		this.branches.clear();
		this.branches.addAll(branches);
		this.securityVersion++;
	}

	public UUID getId() { return id; }
	public String getUsername() { return username; }
	public String getFullName() { return fullName; }
	public String getIssuer() { return issuer; }
	public String getSubject() { return subject; }
	public boolean isAllBranches() { return allBranches; }
	public boolean isActive() { return active; }
	public long getSecurityVersion() { return securityVersion; }
	public Instant getCreatedAt() { return createdAt; }
	public long getVersion() { return version; }
	public Set<Branch> getBranches() { return Set.copyOf(branches); }
}
