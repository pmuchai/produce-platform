package com.primeroot.platform.access;

import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffAccessRequestService {
	private final StaffAccessRequestRepository requests;
	private final StaffUserRepository users;
	private final AuditRepository audit;

	public StaffAccessRequestService(StaffAccessRequestRepository requests, StaffUserRepository users,
			AuditRepository audit) {
		this.requests = requests;
		this.users = users;
		this.audit = audit;
	}

	// The pending request must survive the deliberately unsuccessful application login.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(OidcUser identity) {
		var issuer = identity.getIssuer().toString();
		var subject = identity.getSubject();
		if (users.existsByIssuerAndSubject(issuer, subject)
				|| requests.findByIssuerAndSubject(issuer, subject).isPresent()) return;
		var request = requests.saveAndFlush(new StaffAccessRequest(issuer, subject,
			identity.getPreferredUsername(), identity.getFullName()));
		audit.save(new AuditEvent(null, "STAFF_ACCESS_REQUESTED", "ACCESS_REQUEST", request.getId()));
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public boolean isPending(OidcUser identity) {
		return requests.existsByIssuerAndSubjectAndApprovedFalse(identity.getIssuer().toString(), identity.getSubject());
	}
}
