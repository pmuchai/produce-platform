package com.primeroot.platform.access;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.primeroot.platform.security.StaffPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccessService {
	private final StaffUserRepository users;
	private final BranchRepository branches;

	public AccessService(StaffUserRepository users, BranchRepository branches) {
		this.users = users;
		this.branches = branches;
	}

	public StaffUser currentUser() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
			throw new AuthenticationCredentialsNotFoundException("OIDC authentication is required");
		}
		return users.findById(principal.getStaffId()).filter(StaffUser::isActive)
			.orElseThrow(() -> new AccessDeniedException("Staff access is not active"));
	}

	public Set<Permission> permissions() {
		currentUser();
		var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
		return java.util.Arrays.stream(Permission.values())
			.filter(permission -> authorities.stream().anyMatch(authority -> authority.getAuthority().equals(permission.name())))
			.collect(Collectors.toUnmodifiableSet());
	}

	public StaffUser requirePermission(Permission permission) {
		var user = currentUser();
		if (!permissions().contains(permission)) {
			throw new AccessDeniedException("Required permission: " + permission.name());
		}
		return user;
	}

	public StaffUser requireGlobalPermission(Permission permission) {
		var user = requirePermission(permission);
		if (!user.isAllBranches()) {
			throw new AccessDeniedException("Business-wide scope is required for this operation");
		}
		return user;
	}

	public void requireBranch(UUID branchId) {
		var user = currentUser();
		if (!user.isAllBranches() && branches.findAccessibleBranches(user.getId())
				.stream().noneMatch(branch -> branch.getId().equals(branchId))) {
			throw new AccessDeniedException("Access to this branch is not permitted");
		}
	}
}
