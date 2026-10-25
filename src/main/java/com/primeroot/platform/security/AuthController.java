package com.primeroot.platform.security;

import java.util.Arrays;
import java.util.List;

import com.primeroot.platform.access.AccessDtos.CurrentUserResponse;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
	private final StaffService users;

	public AuthController(StaffService users) {
		this.users = users;
	}

	@GetMapping("/api/auth/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getToken(), token.getHeaderName(), token.getParameterName());
	}

	@GetMapping("/api/auth/me")
	public CurrentUserResponse me() {
		return users.me();
	}

	@GetMapping("/api/permissions")
	public List<PermissionResponse> permissions() {
		return Arrays.stream(Permission.values())
			.map(permission -> new PermissionResponse(permission.name(), permission.getDescription())).toList();
	}

	public record PermissionResponse(String code, String description) {
	}

	public record CsrfResponse(String token, String headerName, String parameterName) {
	}
}
