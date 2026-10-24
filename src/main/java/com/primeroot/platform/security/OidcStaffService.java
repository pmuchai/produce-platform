package com.primeroot.platform.security;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffUserRepository;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OidcStaffService {
	private final StaffUserRepository staff;
	private final AuditRepository audit;

	public OidcStaffService(StaffUserRepository staff, AuditRepository audit) {
		this.staff = staff;
		this.audit = audit;
	}

	@Transactional
	public StaffPrincipal approve(OidcUser identity) {
		var roles = identity.getIdToken().getClaims().get("app_roles");
		if (!(roles instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
			throw denied("The required app_roles claim is missing or invalid");
		}
		var user = staff.findByIssuerAndSubject(identity.getIssuer().toString(), identity.getSubject())
			.filter(account -> account.isActive())
			.orElseThrow(() -> denied("Staff access has not been approved"));
		var permissions = Arrays.stream(Permission.values()).filter(permission -> values.contains(permission.name()))
			.collect(Collectors.toUnmodifiableSet());
		audit.save(new AuditEvent(user.getId(), "OIDC_LOGIN", "USER", user.getId()));
		return new StaffPrincipal(user, identity, permissions);
	}

	private OAuth2AuthenticationException denied(String message) {
		return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), message);
	}
}
