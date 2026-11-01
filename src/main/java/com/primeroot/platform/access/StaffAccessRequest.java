package com.primeroot.platform.access;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff_access_requests")
public class StaffAccessRequest {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 1024, updatable = false)
	private String issuer;

	@Column(nullable = false, length = 255, updatable = false)
	private String subject;

	@Column(nullable = false, length = 64, updatable = false)
	private String suggestedUsername;

	@Column(nullable = false, length = 120, updatable = false)
	private String fullName;

	@Column(nullable = false)
	private boolean approved;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected StaffAccessRequest() {
	}

	public StaffAccessRequest(String issuer, String subject, String username, String fullName) {
		this.issuer = issuer;
		this.subject = subject;
		var suggestion = username == null ? "" : username.strip().toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9._-]", ".").replaceAll("^[._-]+", "");
		this.suggestedUsername = suggestion.length() < 3 ? "staff." + UUID.randomUUID().toString().substring(0, 8)
			: shorten(suggestion, 64);
		this.fullName = fullName == null || fullName.isBlank()
			? this.suggestedUsername : shorten(fullName.strip(), 120);
	}

	private static String shorten(String value, int maximum) {
		int end = Math.min(value.length(), maximum);
		if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
		return value.substring(0, end);
	}

	public UUID getId() { return id; }
	public String getIssuer() { return issuer; }
	public String getSubject() { return subject; }
	public String getSuggestedUsername() { return suggestedUsername; }
	public String getFullName() { return fullName; }
	public boolean isApproved() { return approved; }
	public Instant getCreatedAt() { return createdAt; }

	void approve() { approved = true; }
}
