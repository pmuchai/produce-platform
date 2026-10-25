package com.primeroot.platform.security;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.primeroot.platform.access.StaffUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionValidationFilter extends OncePerRequestFilter {
	private final StaffUserRepository users;
	private final SecurityErrorWriter errors;
	private final Duration maximumAge;

	SessionValidationFilter(StaffUserRepository users, SecurityErrorWriter errors, Duration maximumAge) {
		this.users = users;
		this.errors = errors;
		if (maximumAge.isNegative() || maximumAge.isZero() || maximumAge.compareTo(Duration.ofMinutes(30)) > 0) {
			throw new IllegalArgumentException("Maximum authentication age must be positive and at most 30 minutes");
		}
		this.maximumAge = maximumAge;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof StaffPrincipal principal) {
			var now = Instant.now();
			var valid = now.isBefore(principal.getIdToken().getExpiresAt())
				&& now.isBefore(principal.getAuthenticatedAt().plus(maximumAge))
				&& users.findById(principal.getStaffId())
				.filter(user -> user.isActive()
					&& user.getIssuer().equals(principal.getIssuer().toString())
					&& user.getSubject().equals(principal.getSubject())
					&& user.getSecurityVersion() == principal.getSecurityVersion())
				.isPresent();
			if (!valid) {
				SecurityContextHolder.clearContext();
				var session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				if ("GET".equals(request.getMethod())
						&& ("/".equals(request.getServletPath()) || "/login".equals(request.getServletPath())
							|| "/home".equals(request.getServletPath())
							|| request.getServletPath().startsWith("/ui/"))) {
					response.sendRedirect(request.getContextPath() + "/login");
					return;
				}
				errors.write(request, response, HttpStatus.UNAUTHORIZED, "Sign in again to continue");
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
