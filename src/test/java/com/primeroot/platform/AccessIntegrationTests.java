package com.primeroot.platform;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.primeroot.platform.access.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessIntegrationTests extends OidcTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StaffService staff;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM audit_events");
		jdbc.update("DELETE FROM user_branches");
		jdbc.update("DELETE FROM staff_users");
		jdbc.update("DELETE FROM branches");
		staff.bootstrap("admin", "Platform Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void homeShowsAuthorizedSummariesEscapesNamesAndContainsCsrfProtectedLogout() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			createBranch(admin, "north");
			jdbc.update("UPDATE staff_users SET full_name = ? WHERE subject = ?",
				"<script>alert('name')</script>", OidcTestProvider.ADMIN_SUBJECT);
			var response = admin.get("/home");
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("Welcome, &lt;script&gt;", "1 visible", "1 active branches",
				"Staff access", "1 approved", "Audit activity", "Business-wide",
				"action=\"/api/auth/logout\"", "name=\"_csrf\"")
				.doesNotContain("<script>", "No summary dashboards");
			assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
		}
	}

	@Test
	void homeOnlyShowsBranchScopedSummariesEvenWithGlobalReportingPermissions() throws Exception {
		try (var admin = browser(); var operator = browser()) {
			admin.adminLogin();
			var north = createBranch(admin, "north");
			createBranch(admin, "south");
			assertThat(admin.mutate("POST", "/api/users", Map.of(
				"username", "operator", "fullName", "Branch Operator", "subject", "operator-subject",
				"allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(201);
			assertThat(operator.login("operator-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE)
				.statusCode()).isEqualTo(200);
			var home = operator.get("/home").body();
			assertThat(home).contains("Welcome, Branch Operator", "Assigned active branches", "1 visible")
				.doesNotContain("Staff access", "Audit activity", "/api/users", "/api/audit-events", "2 visible");
		}
	}

	@Test
	void homeWithoutPermissionsHasAnExplicitEmptyStateAndAnonymousVisitorsMustSignIn() throws Exception {
		try (var browser = browser()) {
			var anonymous = browser.get("/home");
			assertThat(anonymous.statusCode()).isEqualTo(302);
			assertThat(anonymous.headers().firstValue("Location").orElseThrow())
				.endsWith("/oauth2/authorization/keycloak");
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, List.of("ADMIN"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(browser.get("/home").body()).contains("No summary dashboards", "No business permissions assigned")
				.doesNotContain("Staff access", "Audit activity", "/api/branches");
		}
	}

	@Test
	void singleMigrationCreatesOnlyTheExternalIdentitySchemaAndEnforcesForeignKeys() {
		assertThat(jdbc.queryForList(
			"SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = TRUE AND \"version\" IS NOT NULL", String.class))
			.containsExactly("1");
		assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'", String.class))
			.contains("BRANCHES", "STAFF_USERS", "USER_BRANCHES", "AUDIT_EVENTS")
			.doesNotContain("ROLES", "PERMISSIONS", "ROLE_PERMISSIONS");
		assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'STAFF_USERS'", String.class))
			.contains("ISSUER", "SUBJECT", "ALL_BRANCHES").doesNotContain("PASSWORD_HASH", "ROLE", "ROLE_ID");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO user_branches(user_id, branch_id) VALUES (?, ?)",
			UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void authorizationRequestUsesPkceAndExactCallbackWithoutExposingClientSecret() throws Exception {
		try (var browser = browser()) {
			var response = browser.get("/oauth2/authorization/keycloak");
			assertThat(response.statusCode()).isEqualTo(302);
			var location = response.headers().firstValue("Location").orElseThrow();
			var parameters = OidcTestProvider.parameters(URI.create(location));
			assertThat(parameters).containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256")
				.containsEntry("redirect_uri", "http://localhost:" + port + "/login/oauth2/code/keycloak");
			assertThat(parameters.get("code_challenge")).hasSize(43);
			assertThat(parameters.get("nonce")).isNotBlank();
			assertThat(parameters.get("state")).isNotBlank();
			assertThat(location).doesNotContain(OidcTestProvider.CLIENT_SECRET, "code_verifier");
			assertThat(response.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
		}
	}

	@Test
	void signedOidcLoginUsesApprovedSubjectAndOnlyKnownPermissionClaims() throws Exception {
		try (var browser = browser()) {
			var response = browser.login(OidcTestProvider.ADMIN_SUBJECT,
				List.of("ADMIN", "DIRECTOR", "BRANCH_VIEW", "NOT_A_SYSTEM_PERMISSION"), OidcTestProvider.Fault.NONE);
			assertThat(response.statusCode()).isEqualTo(200);
			var body = browser.body(response);
			assertThat(body.get("staff").get("username").asText()).isEqualTo("admin");
			assertThat(body.get("staff").get("subject").asText()).isEqualTo(OidcTestProvider.ADMIN_SUBJECT);
			assertThat(body.get("permissions").toString()).isEqualTo("[\"BRANCH_VIEW\"]");
			assertThat(browser.get("/api/branches").statusCode()).isEqualTo(200);
			assertThat(browser.get("/api/users").statusCode()).isEqualTo(403);
			assertThat(response.body()).doesNotContain("id_token", "access_token", "password");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'OIDC_LOGIN'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void jobFunctionNameAloneDoesNotGrantAdministrativeAccess() throws Exception {
		try (var browser = browser()) {
			var response = browser.login(OidcTestProvider.ADMIN_SUBJECT, List.of("ADMIN"), OidcTestProvider.Fault.NONE);
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(browser.body(response).get("permissions").isEmpty()).isTrue();
			assertThat(browser.get("/api/branches").statusCode()).isEqualTo(403);
			assertThat(browser.get("/api/permissions").statusCode()).isEqualTo(200);
		}
	}

	@Test
	void unknownSubjectsAreDeniedAndNeverAutomaticallyLinkedOrCreated() throws Exception {
		try (var browser = browser()) {
			var response = browser.login("unknown-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
			assertThat(response.statusCode()).isEqualTo(403);
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void missingOrMalformedRolesFailClosed() throws Exception {
		try (var browser = browser()) {
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, null, OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, "ADMIN", OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, List.of("BRANCH_VIEW", 7), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
		}
	}

	@ParameterizedTest
	@EnumSource(value = OidcTestProvider.Fault.class, names = {"ISSUER", "AUDIENCE", "SIGNATURE", "NONCE", "EXPIRED"})
	void invalidSignedTokensCannotCreateAnApplicationSession(OidcTestProvider.Fault fault) throws Exception {
		try (var browser = browser()) {
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, fault).statusCode()).isEqualTo(401);
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
		}
	}

	@Test
	void callbackWithWrongStateIsRejected() throws Exception {
		try (var browser = browser()) {
			PROVIDER.identity(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
			var start = browser.get("/oauth2/authorization/keycloak");
			var authorization = browser.send("GET", URI.create(start.headers().firstValue("Location").orElseThrow()), null, null);
			var callback = URI.create(authorization.headers().firstValue("Location").orElseThrow());
			var parameters = OidcTestProvider.parameters(callback);
			var response = browser.get("/login/oauth2/code/keycloak?code=" + parameters.get("code") + "&state=wrong");
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
		}
	}

	@Test
	void expiredIdentityTokenRevokesTheApplicationSession() throws Exception {
		try (var browser = browser()) {
			assertThat(browser.login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS,
				OidcTestProvider.Fault.SHORT_LIVED).statusCode()).isEqualTo(200);
			TimeUnit.SECONDS.sleep(4);
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
		}
	}

	@Test
	void branchesAndStaffHaveExplicitScopeAndLocalChangesRevokeSessions() throws Exception {
		try (var admin = browser(); var operator = browser()) {
			admin.adminLogin();
			var north = createBranch(admin, "north");
			var south = createBranch(admin, "south");
			var provision = admin.mutate("POST", "/api/users", Map.of(
				"username", "operator", "fullName", "Branch Operator", "subject", "operator-subject",
				"allBranches", false, "branchIds", Set.of(north)));
			assertThat(provision.statusCode()).isEqualTo(201);
			var id = admin.body(provision).get("id").asText();
			assertThat(operator.login("operator-subject", List.of("BRANCH_VIEW", "BRANCH_MANAGE", "USER_MANAGE"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/branches").body()).contains(north).doesNotContain(south);
			assertThat(operator.get("/api/branches/" + south).statusCode()).isEqualTo(403);
			assertThat(operator.mutate("PUT", "/api/branches/" + south, branch("south", true)).statusCode()).isEqualTo(403);
			assertThat(operator.mutate("PUT", "/api/branches/" + north, branch("north", true)).statusCode()).isEqualTo(200);
			assertThat(operator.mutate("POST", "/api/branches", branch("extra", true)).statusCode()).isEqualTo(403);
			assertThat(operator.get("/api/users").statusCode()).isEqualTo(403);
			assertThat(operator.mutate("POST", "/api/users", Map.of("username", "forbidden", "fullName", "Forbidden",
				"subject", "forbidden", "allBranches", true, "branchIds", Set.of())).statusCode()).isEqualTo(403);
			assertThat(admin.mutate("PUT", "/api/users/" + id, Map.of(
				"fullName", "Branch Operator", "active", true, "allBranches", false, "branchIds", Set.of(south))).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(operator.login("operator-subject", List.of("BRANCH_VIEW"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/branches").body()).contains(south).doesNotContain(north);
			assertThat(admin.mutate("PUT", "/api/users/" + id, Map.of(
				"fullName", "Branch Operator", "active", false, "allBranches", false, "branchIds", Set.of(south))).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(operator.login("operator-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
		}
	}

	@Test
	void invalidAssignmentsAndDuplicateIdentitiesDoNotPartiallyProvisionStaff() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var north = createBranch(admin, "north");
			var invalid = Map.of("username", "invalid", "fullName", "Invalid", "subject", "new-subject",
				"allBranches", false, "branchIds", Set.of(UUID.randomUUID().toString()));
			assertThat(admin.mutate("POST", "/api/users", invalid).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "invalid", "fullName", "Invalid",
				"subject", "new-subject", "allBranches", false, "branchIds", Set.of())).statusCode()).isEqualTo(400);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(1);
			var valid = Map.of("username", "operator", "fullName", "Operator", "subject", "operator-subject",
				"allBranches", false, "branchIds", Set.of(north));
			assertThat(admin.mutate("POST", "/api/users", valid).statusCode()).isEqualTo(201);
			assertThat(admin.mutate("POST", "/api/users", valid).statusCode()).isEqualTo(409);
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "different", "fullName", "Different",
				"subject", "operator-subject", "allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'STAFF_APPROVED'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void inactiveBranchesDisappearFromAssignedStaffAndCannotBeAssignedToActiveStaff() throws Exception {
		try (var admin = browser(); var operator = browser()) {
			admin.adminLogin();
			var north = createBranch(admin, "north");
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "operator", "fullName", "Operator",
				"subject", "operator-subject", "allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(201);
			assertThat(operator.login("operator-subject", List.of("BRANCH_VIEW"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("PUT", "/api/branches/" + north, branch("north", false)).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/branches").body()).isEqualTo("[]");
			assertThat(operator.get("/api/branches/" + north).statusCode()).isEqualTo(403);
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "blocked", "fullName", "Blocked",
				"subject", "blocked-subject", "allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(400);
		}
	}

	@Test
	void csrfValidationAuditAndSelfLockoutProtectionRemainInPlace() throws Exception {
		try (var admin = browser()) {
			var me = admin.adminLogin();
			var id = admin.body(me).get("staff").get("id").asText();
			var direct = admin.send("POST", URI.create("http://localhost:" + port + "/api/branches"), branch("north", true), null);
			assertThat(direct.statusCode()).isEqualTo(403);
			assertThat(admin.mutate("POST", "/api/branches", branch("!", true)).statusCode()).isEqualTo(400);
			var north = createBranch(admin, "north");
			assertThat(admin.mutate("POST", "/api/branches", branch("NORTH", true)).statusCode()).isEqualTo(409);
			assertThat(admin.get("/api/branches/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
			assertThat(admin.mutate("PUT", "/api/users/" + id, Map.of(
				"fullName", "Administrator", "active", false, "allBranches", true, "branchIds", Set.of())).statusCode()).isEqualTo(409);
			assertThat(admin.get("/api/audit-events").statusCode()).isEqualTo(200);
			assertThat(admin.get("/api/audit-events").body()).contains("BRANCH_CREATED", north, "STAFF_BOOTSTRAPPED", "OIDC_LOGIN");
		}
	}

	@Test
	void logoutRequiresCsrfEndsTheSessionAndReturnsThroughProviderLogoutToKeycloakLogin() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			assertThat(admin.send("POST", URI.create("http://localhost:" + port + "/api/auth/logout"), null, null).statusCode()).isEqualTo(403);
			var logout = admin.mutate("POST", "/api/auth/logout", null);
			assertThat(logout.statusCode()).isEqualTo(302);
			var location = URI.create(logout.headers().firstValue("Location").orElseThrow());
			assertThat(location.toString()).startsWith(PROVIDER.issuer() + "/logout");
			assertThat(OidcTestProvider.parameters(location)).containsEntry("post_logout_redirect_uri", "http://localhost:" + port + "/");
			var providerLogout = admin.send("GET", location, null, null);
			assertThat(providerLogout.statusCode()).isEqualTo(302);
			assertThat(admin.get("/api/auth/me").statusCode()).isEqualTo(401);
			var landing = admin.get(providerLogout.headers().firstValue("Location").orElseThrow());
			assertThat(landing.statusCode()).isEqualTo(302);
			assertThat(landing.headers().firstValue("Location").orElseThrow())
				.isEqualTo("/oauth2/authorization/keycloak");
			var login = admin.get(landing.headers().firstValue("Location").orElseThrow());
			assertThat(login.statusCode()).isEqualTo(302);
			var authorization = URI.create(login.headers().firstValue("Location").orElseThrow());
			assertThat(authorization.toString()).startsWith(PROVIDER.issuer() + "/auth?");
			assertThat(OidcTestProvider.parameters(authorization))
				.containsEntry("code_challenge_method", "S256").containsKey("state").containsKey("nonce");
			assertThat(admin.get("/api/auth/me").statusCode()).isEqualTo(401);
		}
	}

	@Test
	void bootstrapIsExplicitIdempotentAndNeverReactivatesAnExistingIdentity() {
		assertThat(staff.bootstrap("admin", "Administrator", OidcTestProvider.ADMIN_SUBJECT)).isFalse();
		assertThatThrownBy(() -> staff.bootstrap("another", "Another", ""))
			.isInstanceOf(IllegalStateException.class);
		jdbc.update("UPDATE staff_users SET active = FALSE WHERE subject = ?", OidcTestProvider.ADMIN_SUBJECT);
		assertThatThrownBy(() -> staff.bootstrap("admin", "Administrator", OidcTestProvider.ADMIN_SUBJECT))
			.isInstanceOf(IllegalStateException.class);
	}

	private String createBranch(Browser admin, String code) throws Exception {
		var response = admin.mutate("POST", "/api/branches", branch(code, true));
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
		return admin.body(response).get("id").asText();
	}

	private Map<String, Object> branch(String code, boolean active) {
		return Map.of("code", code, "name", "Branch " + code, "address", "Market Road", "active", active);
	}
}
