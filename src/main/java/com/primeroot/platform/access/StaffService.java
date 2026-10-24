package com.primeroot.platform.access;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.CreateUserRequest;
import com.primeroot.platform.access.AccessDtos.CurrentUserResponse;
import com.primeroot.platform.access.AccessDtos.UpdateUserRequest;
import com.primeroot.platform.access.AccessDtos.UserResponse;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class StaffService {
	private final StaffUserRepository users;
	private final BranchRepository branches;
	private final AccessService access;
	private final AuditRepository audit;
	private final Validator validator;
	private final String issuer;

	public StaffService(StaffUserRepository users, BranchRepository branches, AccessService access,
			AuditRepository audit, Validator validator,
			@Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuer) {
		this.users = users;
		this.branches = branches;
		this.access = access;
		this.audit = audit;
		this.validator = validator;
		this.issuer = issuer;
	}

	public CurrentUserResponse me() {
		return new CurrentUserResponse(UserResponse.from(access.currentUser()), access.permissions());
	}

	public List<UserResponse> list() {
		access.requireGlobalPermission(Permission.USER_VIEW);
		return users.findAllByOrderByUsername().stream().map(UserResponse::from).toList();
	}

	@Transactional
	public UserResponse create(CreateUserRequest request) {
		var actor = access.requireGlobalPermission(Permission.USER_MANAGE);
		var user = provision(request);
		audit.save(new AuditEvent(actor.getId(), "STAFF_APPROVED", "USER", user.getId()));
		return UserResponse.from(user);
	}

	@Transactional
	public UserResponse update(UUID id, UpdateUserRequest request) {
		var actor = access.requireGlobalPermission(Permission.USER_MANAGE);
		var user = users.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
		if (actor.getId().equals(id) && (!request.active() || !request.allBranches())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"Ask another authorized administrator to remove your own staff access");
		}
		user.update(request.fullName(), request.active(), request.allBranches(),
			assignments(request.branchIds(), request.allBranches(), request.active()));
		audit.save(new AuditEvent(actor.getId(), "STAFF_UPDATED", "USER", id));
		return UserResponse.from(user);
	}

	@Transactional
	public boolean bootstrap(String username, String fullName, String subject) {
		var existing = users.findByIssuerAndSubject(issuer, subject);
		if (existing.isPresent()) {
			if (!existing.get().isActive() || !existing.get().isAllBranches()) {
				throw new IllegalStateException("Bootstrap identity already exists without active business-wide access");
			}
			return false;
		}
		var request = new CreateUserRequest(username, fullName, subject, true, Set.of());
		if (!validator.validate(request).isEmpty()) {
			throw new IllegalStateException("Bootstrap requires a valid local username, full name and Keycloak subject");
		}
		var user = provision(request);
		audit.save(new AuditEvent(null, "STAFF_BOOTSTRAPPED", "USER", user.getId()));
		return true;
	}

	private StaffUser provision(CreateUserRequest request) {
		var username = request.username().toLowerCase(Locale.ROOT);
		if (users.existsByUsername(username) || users.existsByIssuerAndSubject(issuer, request.subject())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or identity is already approved");
		}
		return users.saveAndFlush(new StaffUser(username, request.fullName(), issuer, request.subject(),
			request.allBranches(), assignments(request.branchIds(), request.allBranches(), true)));
	}

	private Set<Branch> assignments(Set<UUID> ids, boolean allBranches, boolean active) {
		var assigned = new HashSet<>(branches.findAllById(ids));
		if (assigned.size() != ids.size()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every assigned branch must exist");
		}
		if (active && !allBranches
				&& (assigned.isEmpty() || assigned.stream().anyMatch(branch -> !branch.isActive()))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"Active branch-scoped staff require at least one branch and all assigned branches must be active");
		}
		return assigned;
	}
}
