package com.primeroot.platform.security;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffAccessRequestService;
import com.primeroot.platform.access.StaffUserRepository;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class OidcStaffService {
	private final StaffUserRepository staff;
	private final AuditRepository audit;
	private final StaffAccessRequestService requests;

	public OidcStaffService(StaffUserRepository staff, AuditRepository audit, StaffAccessRequestService requests) {
		this.staff = staff;
		this.audit = audit;
		this.requests = requests;
	}

	public StaffPrincipal approve(OidcUser identity) {
		var roles = identity.getIdToken().getClaims().get("app_roles");
		if (!(roles instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
			throw denied("The required app_roles claim is missing or invalid");
		}
		var permissions = Arrays.stream(Permission.values()).filter(permission -> values.contains(permission.name()))
			.collect(Collectors.toUnmodifiableSet());
		var existing = staff.findByIssuerAndSubject(identity.getIssuer().toString(), identity.getSubject());
		if (existing.isEmpty()) {
			if (permissions.isEmpty()) throw denied("No application permissions have been assigned");
			try {
				requests.record(identity);
			}
			catch (DataIntegrityViolationException exception) {
				if (!requests.isPending(identity)) throw exception;
			}
			throw new OAuth2AuthenticationException(new OAuth2Error("staff_approval_pending"),
				"Your account is awaiting administrator approval");
		}
		var user = existing.get();
		if (!user.isActive()) throw denied("Staff access has not been approved");
		audit.save(new AuditEvent(user.getId(), "OIDC_LOGIN", "USER", user.getId()));
		return new StaffPrincipal(user, identity, permissions);
	}

	private OAuth2AuthenticationException denied(String message) {
		return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), message);
	}
}
