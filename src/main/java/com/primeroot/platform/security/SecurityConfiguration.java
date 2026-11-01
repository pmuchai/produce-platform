package com.primeroot.platform.security;

import java.time.Duration;

import com.primeroot.platform.access.StaffUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfiguration {
	@Bean
	OAuth2UserService<OidcUserRequest, OidcUser> staffOidcUserService(OidcStaffService staff) {
		var delegate = new OidcUserService();
		delegate.setRetrieveUserInfo(request -> false);
		return request -> staff.approve(delegate.loadUser(request));
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, StaffUserRepository staff,
			SecurityErrorWriter errors, ClientRegistrationRepository registrations,
			OAuth2UserService<OidcUserRequest, OidcUser> staffOidcUserService,
			@Value("${platform.security.maximum-authentication-age:PT5M}") Duration maximumAge) throws Exception {
		var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		var logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
		logout.setPostLogoutRedirectUri("{baseUrl}/");
		http
			.csrf(Customizer.withDefaults())
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.GET, "/", "/login", "/access-pending", "/actuator/health", "/api/auth/csrf",
					"/assets/app.css", "/assets/app.js", "/assets/prime-root-mark.svg",
					"/assets/produce-distribution.svg").permitAll()
				.requestMatchers("/oauth2/authorization/keycloak", "/login/oauth2/code/keycloak").permitAll()
				.requestMatchers("/home", "/ui/**", "/api/**").authenticated()
				.anyRequest().denyAll())
			.requestCache(cache -> cache.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.oauth2Login(login -> login
				.loginPage("/login")
				.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
				.userInfoEndpoint(endpoint -> endpoint.oidcUserService(staffOidcUserService))
				.successHandler((request, response, authentication) ->
					response.sendRedirect(request.getContextPath() + "/home"))
				.failureHandler((request, response, exception) -> {
					if (exception instanceof OAuth2AuthenticationException oauth
							&& "staff_approval_pending".equals(oauth.getError().getErrorCode())) {
						response.sendRedirect(request.getContextPath() + "/access-pending");
					}
					else {
						errors.write(request, response,
							exception instanceof OAuth2AuthenticationException oauth
								&& "access_denied".equals(oauth.getError().getErrorCode())
									? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
							"OIDC login failed or staff access is not approved");
					}
				}))
			.logout(config -> config.logoutUrl("/api/auth/logout").logoutSuccessHandler(logout))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, exception) -> {
					if ("/home".equals(request.getServletPath()) || request.getServletPath().startsWith("/ui/")) {
						response.sendRedirect(request.getContextPath() + "/oauth2/authorization/keycloak");
					}
					else {
						errors.write(request, response, HttpStatus.UNAUTHORIZED, "Sign in through Keycloak");
					}
				})
				.accessDeniedHandler((request, response, exception) ->
					errors.write(request, response, HttpStatus.FORBIDDEN, "Access denied or CSRF token missing or invalid")))
			.addFilterBefore(new SessionValidationFilter(staff, errors, maximumAge), AuthorizationFilter.class);
		return http.build();
	}
}
