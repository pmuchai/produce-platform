package com.primeroot.platform.security;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class StaffPrincipal implements OidcUser, Serializable {
	@Serial
	private static final long serialVersionUID = 2L;

	private final UUID staffId;
	private final long securityVersion;
	private final Instant authenticatedAt = Instant.now();
	private final OidcUser identity;
	private final Collection<? extends GrantedAuthority> authorities;

	public StaffPrincipal(StaffUser staff, OidcUser identity, Set<Permission> permissions) {
		this.staffId = staff.getId();
		this.securityVersion = staff.getSecurityVersion();
		this.identity = identity;
		this.authorities = permissions.stream()
			.map(permission -> new SimpleGrantedAuthority(permission.name())).toList();
	}

	public UUID getStaffId() { return staffId; }
	public long getSecurityVersion() { return securityVersion; }
	public Instant getAuthenticatedAt() { return authenticatedAt; }

	@Override
	public String getName() { return identity.getSubject(); }

	@Override
	public Map<String, Object> getAttributes() { return identity.getAttributes(); }

	@Override
	public Map<String, Object> getClaims() { return identity.getClaims(); }

	@Override
	public OidcIdToken getIdToken() { return identity.getIdToken(); }

	@Override
	public OidcUserInfo getUserInfo() { return identity.getUserInfo(); }

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
}
