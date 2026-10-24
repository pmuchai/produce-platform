package com.primeroot.platform.access;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccessDtos {
	private AccessDtos() {
	}

	public record BranchRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{1,31}") String code,
		@NotBlank @Size(max = 120) String name,
		@Size(max = 255) String address,
		@NotNull Boolean active) {
	}

	public record BranchResponse(UUID id, String code, String name, String address,
			boolean active, Instant createdAt, long version) {
		public static BranchResponse from(Branch branch) {
			return new BranchResponse(branch.getId(), branch.getCode(), branch.getName(),
				branch.getAddress(), branch.isActive(), branch.getCreatedAt(), branch.getVersion());
		}
	}

	public record CreateUserRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
		@NotBlank @Size(max = 120) String fullName,
		@NotBlank @Size(max = 255) String subject,
		@NotNull Boolean allBranches,
		@NotNull @Size(max = 100) Set<@NotNull UUID> branchIds) {
	}

	public record UpdateUserRequest(
		@NotBlank @Size(max = 120) String fullName,
		@NotNull Boolean active,
		@NotNull Boolean allBranches,
		@NotNull @Size(max = 100) Set<@NotNull UUID> branchIds) {
	}

	public record UserResponse(UUID id, String username, String fullName, String issuer, String subject,
			boolean active, Set<UUID> branchIds, boolean allBranches, Instant createdAt, long version) {
		public static UserResponse from(StaffUser user) {
			var branchIds = user.getBranches().stream().map(Branch::getId)
				.collect(java.util.stream.Collectors.toSet());
			return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
				user.getIssuer(), user.getSubject(), user.isActive(), branchIds,
				user.isAllBranches(), user.getCreatedAt(), user.getVersion());
		}
	}

	public record CurrentUserResponse(UserResponse staff, Set<Permission> permissions) {
	}
}
