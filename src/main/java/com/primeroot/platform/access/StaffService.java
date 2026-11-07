package com.primeroot.platform.access;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.CreateUserRequest;
import com.primeroot.platform.access.AccessDtos.AccessRequestResponse;
import com.primeroot.platform.access.AccessDtos.ApproveAccessRequest;
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
	private final StaffAccessRequestRepository requests;
	private final BranchRepository branches;
	private final AccessService access;
	private final AuditRepository audit;
	private final StaffIdentityLock identityLock;
	private final Validator validator;
	private final String issuer;

	public StaffService(StaffUserRepository users, StaffAccessRequestRepository requests, BranchRepository branches, AccessService access,
			AuditRepository audit, StaffIdentityLock identityLock, Validator validator,
			@Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuer) {
		this.users = users;
		this.requests = requests;
		this.branches = branches;
		this.access = access;
		this.audit = audit;
		this.identityLock = identityLock;
		this.validator = validator;
		this.issuer = issuer;
	}

	public CurrentUserResponse me() {
		return new CurrentUserResponse(UserResponse.from(access.currentUser()), access.permissions());
	}

	public boolean canCreateSale() {
		var user = access.currentUser();
		return access.permissions().contains(Permission.SALE_CREATE) && !user.isAllBranches()
			&& user.getBranches().size() == 1 && user.getBranches().iterator().next().isActive();
	}

	public List<UserResponse> list() {
		access.requireGlobalPermission(Permission.USER_VIEW);
		return users.findAllByOrderByUsername().stream().map(UserResponse::from).toList();
	}

	public List<AccessRequestResponse> pendingRequests() {
		access.requireGlobalPermission(Permission.USER_VIEW);
		return requests.findByIssuerAndApprovedFalseOrderByCreatedAtAsc(issuer).stream()
			.map(AccessRequestResponse::from).toList();
	}

	@Transactional
	public UserResponse approveRequest(UUID id, ApproveAccessRequest approval) {
		access.requireGlobalPermission(Permission.USER_MANAGE);
		identityLock.lock();
		var request = requests.lockById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Access request not found"));
		if (request.isApproved() || !issuer.equals(request.getIssuer())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"Access request is already approved or belongs to a different configured issuer");
		}
		return create(new CreateUserRequest(approval.username(), approval.fullName(), request.getSubject(),
			approval.allBranches(), approval.branchIds()));
	}

	@Transactional
	public UserResponse create(CreateUserRequest request) {
		var actor = access.requireGlobalPermission(Permission.USER_MANAGE);
		identityLock.lock();
		var pending = requests.findByIssuerAndSubject(issuer, request.subject());
		var user = provision(request);
		audit.save(new AuditEvent(actor.getId(), "STAFF_APPROVED", "USER", user.getId()));
		completeRequest(pending, actor.getId());
		return UserResponse.from(user);
	}

	@Transactional
	public UserResponse update(UUID id, UpdateUserRequest request, String ifMatch) {
		var actor = access.requireGlobalPermission(Permission.USER_MANAGE);
		var user = users.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
		requireMatchingVersion(ifMatch, user.getVersion());
		if (actor.getId().equals(id) && (!request.active() || !request.allBranches())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"Ask another authorized administrator to remove your own staff access");
		}
		user.update(request.fullName(), request.active(), request.allBranches(),
			assignments(request.branchIds(), request.allBranches(), request.active()));
		users.saveAndFlush(user);
		audit.save(new AuditEvent(actor.getId(), "STAFF_UPDATED", "USER", id));
		return UserResponse.from(user);
	}

	private static void requireMatchingVersion(String ifMatch, long version) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
				"An If-Match header is required");
		}
		if (!ifMatch.equals("\"" + version + "\"")) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
				"The staff record changed; reload it and try again");
		}
	}

	@Transactional
	public boolean bootstrap(String username, String fullName, String subject) {
		identityLock.lock();
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
		var pending = requests.findByIssuerAndSubject(issuer, subject);
		var user = provision(request);
		audit.save(new AuditEvent(null, "STAFF_BOOTSTRAPPED", "USER", user.getId()));
		completeRequest(pending, null);
		return true;
	}

	private void completeRequest(Optional<StaffAccessRequest> pending, UUID actorId) {
		pending.filter(request -> !request.isApproved()).ifPresent(request -> {
			request.approve();
			audit.save(new AuditEvent(actorId, "STAFF_ACCESS_APPROVED", "ACCESS_REQUEST", request.getId()));
		});
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
