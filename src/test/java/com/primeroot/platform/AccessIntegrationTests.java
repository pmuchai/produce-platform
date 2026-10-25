package com.primeroot.platform;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
		jdbc.update("DELETE FROM product_packaging");
		jdbc.update("DELETE FROM products");
		jdbc.update("DELETE FROM product_classifications");
		jdbc.update("DELETE FROM produce_types");
		jdbc.update("DELETE FROM units_of_measure WHERE code NOT IN ('KG', 'G', 'PIECE', 'CRATE', 'BAG')");
		jdbc.update("UPDATE units_of_measure SET active = TRUE");
		jdbc.update("DELETE FROM user_branches");
		jdbc.update("DELETE FROM staff_users");
		jdbc.update("DELETE FROM branches");
		staff.bootstrap("admin", "Platform Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void cataloguePagesRenderAndProductsConvertOnlyUsingCompatibleExplicitDefinitions() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var type = createCatalogue(admin, "types", Map.of("code", "tomato", "name", "Tomatoes", "active", true));
			var grade = createCatalogue(admin, "classifications", Map.of("code", "tomato_a", "name", "Grade A",
				"active", true, "produceTypeId", type, "kind", "GRADE"));
			var product = createCatalogue(admin, "products", Map.of("code", "tomato_a_kg", "name", "Tomatoes Grade A",
				"active", true, "produceTypeId", type, "gradeId", grade, "baseUnitId", unit("KG")));
			var snapshot = admin.body(admin.get("/api/catalogue"));
			assertThat(snapshot.get("products").size()).isEqualTo(1);
			assertThat(snapshot.get("types").get(0).get("code").asText()).isEqualTo("TOMATO");
			var grams = admin.mutate("POST", "/api/catalogue/convert", Map.of(
				"productId", product, "fromUnitId", unit("G"), "quantity", "1250"));
			assertThat(grams.statusCode()).isEqualTo(200);
			assertThat(admin.body(grams).get("quantity").asText()).isEqualTo("1.250000");
			assertThat(admin.mutate("POST", "/api/catalogue/convert", Map.of(
				"productId", product, "fromUnitId", unit("PIECE"), "quantity", 2)).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/catalogue/convert", Map.of(
				"productId", product, "fromUnitId", unit("CRATE"), "quantity", 2)).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("PUT", "/api/catalogue/packaging", Map.of(
				"productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", "20.5")).statusCode()).isEqualTo(200);
			var crates = admin.mutate("POST", "/api/catalogue/convert", Map.of(
				"productId", product, "fromUnitId", unit("CRATE"), "quantity", 2));
			assertThat(admin.body(crates).get("quantity").asText()).isEqualTo("41.000000");
			assertThat(updateCatalogue(admin, "packaging", null, Map.of(
				"productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", 25)).statusCode()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_packaging", Integer.class)).isEqualTo(1);
			var cataloguePage = admin.get("/ui/catalogue");
			assertThat(cataloguePage.statusCode()).isEqualTo(200);
			assertThat(cataloguePage.body()).contains("Tomatoes Grade A", "data-resource=\"products\"",
				"data-resource=\"units\"", "data-resource=\"packaging\"", "data-resource=\"convert\"",
				"Main navigation", "Receivables / Stock entries", "Stock movement");
			for (var page : List.of("/ui/configurations", "/ui/branches", "/ui/users", "/ui/reports")) {
				assertThat(admin.get(page).statusCode()).as(page).isEqualTo(200);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action LIKE 'CATALOGUE_%'",
				Integer.class)).isEqualTo(5);
		}
	}

	@Test
	void catalogueRejectsWrongClassificationsInactiveDependenciesDuplicatesAndUnsafeUnitChanges() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var tomato = createCatalogue(admin, "types", Map.of("code", "tomato", "name", "Tomatoes", "active", true));
			var onion = createCatalogue(admin, "types", Map.of("code", "onion", "name", "Onions", "active", true));
			var grade = createCatalogue(admin, "classifications", Map.of("code", "onion_a", "name", "Grade A",
				"active", true, "produceTypeId", onion, "kind", "GRADE"));
			assertThat(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "bad", "name", "Wrong grade",
				"active", true, "produceTypeId", tomato, "gradeId", grade, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "bad", "name", "Wrong kind",
				"active", true, "produceTypeId", onion, "varietyId", grade, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "bad", "name", "Packaging base",
				"active", true, "produceTypeId", tomato, "baseUnitId", unit("CRATE"))).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/catalogue/types", Map.of("code", "TOMATO", "name", "Duplicate",
				"active", true)).statusCode()).isEqualTo(409);
			assertThat(admin.mutate("POST", "/api/catalogue/units", Map.of("code", "bad_pack", "name", "Universal crate",
				"active", true, "dimension", "PACKAGING", "baseFactor", 20)).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/catalogue/units", Map.of("code", "bad_mass", "name", "No factor",
				"active", true, "dimension", "MASS")).statusCode()).isEqualTo(400);
			assertThat(updateCatalogue(admin, "units", unit("KG"), Map.of("code", "KG", "name", "Kilogram",
				"active", true, "dimension", "MASS", "baseFactor", 2)).statusCode()).isEqualTo(409);
			var product = createCatalogue(admin, "products", Map.of("code", "onion_a_kg", "name", "Onions A",
				"active", true, "produceTypeId", onion, "gradeId", grade, "baseUnitId", unit("KG")));
			assertThat(updateCatalogue(admin, "classifications", grade, Map.of("code", "onion_a",
				"name", "Grade A", "active", true, "produceTypeId", tomato, "kind", "GRADE")).statusCode()).isEqualTo(409);
			assertThat(updateCatalogue(admin, "types", onion, Map.of("code", "onion",
				"name", "Onions", "active", false)).statusCode()).isEqualTo(409);
			assertThat(updateCatalogue(admin, "classifications", grade, Map.of("code", "onion_a",
				"name", "Grade A", "active", false, "produceTypeId", onion, "kind", "GRADE")).statusCode()).isEqualTo(409);
			assertThat(updateCatalogue(admin, "units", unit("KG"), Map.of("code", "KG", "name", "Kilogram",
				"active", false, "dimension", "MASS", "baseFactor", 1)).statusCode()).isEqualTo(409);
			assertThat(updateCatalogue(admin, "products", product, Map.of("code", "onion_a_kg", "name", "Onions A",
				"active", true, "produceTypeId", onion, "gradeId", grade, "baseUnitId", unit("G"))).statusCode()).isEqualTo(409);
			assertThat(updateCatalogue(admin, "products", product, Map.of("code", "onion_a_kg", "name", "Onions A",
				"active", false, "produceTypeId", onion, "gradeId", grade, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(200);
			assertThat(updateCatalogue(admin, "classifications", grade, Map.of("code", "onion_a",
				"name", "Grade A", "active", false, "produceTypeId", onion, "kind", "GRADE")).statusCode()).isEqualTo(200);
			assertThat(updateCatalogue(admin, "products", product, Map.of("code", "onion_a_kg", "name", "Onions A",
				"active", true, "produceTypeId", onion, "gradeId", grade, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(400);
			assertThat(updateCatalogue(admin, "units", unit("G"), Map.of("code", "G", "name", "Gram",
				"active", true, "dimension", "MASS", "baseFactor", "0.001")).statusCode()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void productCannotBeReactivatedWhileItsPackagingUnitIsInactive() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var type = createCatalogue(admin, "types", Map.of("code", "tomato", "name", "Tomatoes", "active", true));
			var product = createCatalogue(admin, "products", Map.of("code", "tomato_kg", "name", "Tomatoes",
				"active", true, "produceTypeId", type, "baseUnitId", unit("KG")));
			var crate = unit("CRATE");
			assertThat(admin.mutate("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", crate, "quantityInBaseUnit", 20)).statusCode()).isEqualTo(200);
			assertThat(updateCatalogue(admin, "products", product, Map.of("code", "tomato_kg", "name", "Tomatoes",
				"active", false, "produceTypeId", type, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(200);
			assertThat(updateCatalogue(admin, "units", crate, Map.of("code", "CRATE", "name", "Crate",
				"active", false, "dimension", "PACKAGING")).statusCode()).isEqualTo(200);
			assertThat(updateCatalogue(admin, "products", product, Map.of("code", "tomato_kg", "name", "Tomatoes",
				"active", true, "produceTypeId", type, "baseUnitId", unit("KG"))).statusCode()).isEqualTo(409);
		}
	}

	@Test
	void catalogueUpdatesRequireTheVersionLoadedByTheEditor() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var type = createCatalogue(admin, "types", Map.of("code", "tomato", "name", "Tomatoes", "active", true));
			var update = Map.of("code", "tomato", "name", "Fresh Tomatoes", "active", true);
			assertThat(admin.mutate("PUT", "/api/catalogue/types/" + type, update).statusCode()).isEqualTo(428);
			var changed = admin.mutateWithIfMatch("PUT", "/api/catalogue/types/" + type, update, "\"0\"");
			assertThat(changed.statusCode()).isEqualTo(200);
			assertThat(changed.headers().firstValue("ETag")).contains("\"1\"");
			assertThat(admin.mutateWithIfMatch("PUT", "/api/catalogue/types/" + type, update, "\"0\"")
				.statusCode()).isEqualTo(412);

			var product = createCatalogue(admin, "products", Map.of("code", "tomato_product",
				"name", "Tomato Product", "active", true, "produceTypeId", type, "baseUnitId", unit("KG")));
			var createPackaging = Map.of("productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", 20);
			assertThat(admin.mutate("PUT", "/api/catalogue/packaging", createPackaging).statusCode()).isEqualTo(200);
			var snapshot = admin.body(admin.get("/api/catalogue"));
			var packVersion = snapshot.get("packaging").get(0).get("version").asLong();
			assertThat(admin.mutate("PUT", "/api/catalogue/packaging", Map.of(
				"productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", 21)).statusCode()).isEqualTo(428);
			var updatedPackaging = admin.mutateWithIfMatch("PUT", "/api/catalogue/packaging", Map.of(
				"productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", 21), "\"" + packVersion + "\"");
			assertThat(updatedPackaging.statusCode()).isEqualTo(200);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/catalogue/packaging", Map.of(
				"productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", 22),
				"\"" + packVersion + "\"").statusCode()).isEqualTo(412);
		}
	}

	@Test
	void cataloguePermissionsAndGlobalScopeAreEnforcedIndependentlyOfJobFunctionNames() throws Exception {
		try (var admin = browser(); var reader = browser(); var scoped = browser()) {
			admin.adminLogin();
			assertThat(reader.login(OidcTestProvider.ADMIN_SUBJECT, List.of("FINANCE", "CATALOGUE_VIEW"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(reader.get("/ui/catalogue").body()).contains("Read-only catalogue")
				.doesNotContain("data-resource=\"types\"", "data-resource=\"products\"");
			assertThat(reader.mutate("POST", "/api/catalogue/types", Map.of("code", "blocked",
				"name", "Blocked", "active", true)).statusCode()).isEqualTo(403);
			assertThat(reader.get("/ui/users").statusCode()).isEqualTo(403);
			var branch = createBranch(admin, "north");
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "operator", "fullName", "Operator",
				"subject", "operator-subject", "allBranches", false, "branchIds", Set.of(branch))).statusCode()).isEqualTo(201);
			assertThat(scoped.login("operator-subject", List.of("CATALOGUE_VIEW", "CATALOGUE_MANAGE"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(scoped.get("/api/catalogue").statusCode()).isEqualTo(200);
			assertThat(scoped.mutate("POST", "/api/catalogue/types", Map.of("code", "blocked",
				"name", "Blocked", "active", true)).statusCode()).isEqualTo(403);
			assertThat(scoped.get("/ui/catalogue").body()).contains("Read-only catalogue");
			assertThat(admin.login(OidcTestProvider.ADMIN_SUBJECT, List.of("ADMIN"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(admin.get("/ui/catalogue").statusCode()).isEqualTo(403);
			assertThat(admin.get("/api/catalogue").statusCode()).isEqualTo(403);
		}
	}

	@Test
	void dashboardDefaultsToWeeklyAndFiltersOnlyIntervalMetricsWithExclusiveEndBoundaries() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var today = LocalDate.now(ZoneOffset.UTC);
			var start = today.minusDays(3);
			var dayBefore = start.minusDays(1);
			jdbc.update("DELETE FROM audit_events");
			insertAudit("at-start", start.toString() + "T00:00:00Z");
			insertAudit("at-end", today.toString() + "T23:59:59Z");
			insertAudit("previous", dayBefore.toString() + "T23:59:59Z");
			insertAudit("excluded", today.plusDays(1).toString() + "T00:00:00Z");
			var selected = admin.get("/home?start=" + start + "&end=" + today);
			assertThat(selected.statusCode()).isEqualTo(200);
			assertThat(selected.body()).contains("2 events", "+1 vs previous equal-length interval (1)",
				"not historical balances", "Sales transactions", "Not available yet");
			var report = admin.get("/ui/reports?start=" + start + "&end=" + today);
			assertThat(report.body()).contains("at-start", "at-end").doesNotContain(">previous<", ">excluded<");
			assertThat(admin.get("/home").body()).contains("value=\"" +
				today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) + "\"");
			for (var query : List.of("?start=" + today, "?start=" + today + "&end=" + dayBefore,
				"?start=" + start + "&end=" + today.plusDays(1), "?start=2020-01-01&end=" + today,
				"?start=not-a-date&end=" + today)) {
				assertThat(admin.get("/home" + query).statusCode()).as(query).isEqualTo(400);
			}
		}
	}

	private void insertAudit(String action, String timestamp) {
		jdbc.update("INSERT INTO audit_events(id, action, target_type, target_id, occurred_at) VALUES (?, ?, 'TEST', ?, ?)",
			UUID.randomUUID(), action, UUID.randomUUID(), java.time.OffsetDateTime.parse(timestamp));
	}

	private String unit(String code) {
		return jdbc.queryForObject("SELECT id FROM units_of_measure WHERE code = ?", UUID.class, code).toString();
	}

	private String createCatalogue(Browser admin, String resource, Object request) throws Exception {
		var response = admin.mutate("POST", "/api/catalogue/" + resource, request);
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
		return admin.body(response).get("id").asText();
	}

	private HttpResponse<String> updateCatalogue(Browser admin, String resource, String id, Object request)
			throws Exception {
		var snapshot = admin.body(admin.get("/api/catalogue"));
		var items = snapshot.get(resource);
		String version = null;
		for (var item : items) {
			if (resource.equals("packaging")
					? item.get("productId").asText().equals(((Map<?, ?>) request).get("productId"))
						&& item.get("unitId").asText().equals(((Map<?, ?>) request).get("unitId"))
					: item.get("id").asText().equals(id)) {
				version = "\"" + item.get("version").asLong() + "\"";
				break;
			}
		}
		var path = resource.equals("packaging") ? "/api/catalogue/packaging"
			: "/api/catalogue/" + resource + "/" + id;
		return admin.mutateWithIfMatch("PUT", path, request, version);
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
	void migrationsRetainExternalIdentitySchemaAndEnforceForeignKeys() {
		assertThat(jdbc.queryForList(
			"SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = TRUE AND \"version\" IS NOT NULL", String.class))
			.containsExactly("1", "2", "3");
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
	void expiredIdentityTokenRedirectsLandingPagesToSignIn() throws Exception {
		try (var homeBrowser = browser(); var rootBrowser = browser(); var catalogueBrowser = browser()) {
			assertThat(homeBrowser.login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS,
				OidcTestProvider.Fault.SHORT_LIVED).statusCode()).isEqualTo(200);
			assertThat(rootBrowser.login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS,
				OidcTestProvider.Fault.SHORT_LIVED).statusCode()).isEqualTo(200);
			assertThat(catalogueBrowser.login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS,
				OidcTestProvider.Fault.SHORT_LIVED).statusCode()).isEqualTo(200);
			TimeUnit.SECONDS.sleep(4);
			for (var response : List.of(homeBrowser.get("/home"), rootBrowser.get("/"),
					catalogueBrowser.get("/ui/catalogue"))) {
				assertThat(response.statusCode()).isEqualTo(302);
				assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath())
					.isEqualTo("/oauth2/authorization/keycloak");
			}
			assertThat(homeBrowser.get("/api/auth/me").statusCode()).isEqualTo(401);
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
			var northEtag = operator.get("/api/branches/" + north).headers().firstValue("ETag").orElseThrow();
			assertThat(operator.mutateWithIfMatch("PUT", "/api/branches/" + north, branch("north", true), northEtag)
				.statusCode()).isEqualTo(200);
			assertThat(operator.mutate("POST", "/api/branches", branch("extra", true)).statusCode()).isEqualTo(403);
			assertThat(operator.get("/api/users").statusCode()).isEqualTo(403);
			assertThat(operator.mutate("POST", "/api/users", Map.of("username", "forbidden", "fullName", "Forbidden",
				"subject", "forbidden", "allBranches", true, "branchIds", Set.of())).statusCode()).isEqualTo(403);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + id, Map.of(
				"fullName", "Branch Operator", "active", true, "allBranches", false, "branchIds", Set.of(south)),
				provision.headers().firstValue("ETag").orElseThrow()).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(operator.login("operator-subject", List.of("BRANCH_VIEW"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(operator.get("/api/branches").body()).contains(south).doesNotContain(north);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + id, Map.of(
				"fullName", "Branch Operator", "active", false, "allBranches", false, "branchIds", Set.of(south)),
				userEtag(admin, id)).statusCode()).isEqualTo(200);
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
			var etag = admin.get("/api/branches/" + north).headers().firstValue("ETag").orElseThrow();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/branches/" + north, branch("north", false), etag)
				.statusCode()).isEqualTo(200);
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
			assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + id, Map.of(
				"fullName", "Administrator", "active", false, "allBranches", true, "branchIds", Set.of()),
				userEtag(admin, id)).statusCode()).isEqualTo(409);
			assertThat(admin.get("/api/audit-events").statusCode()).isEqualTo(200);
			assertThat(admin.get("/api/audit-events").body()).contains("BRANCH_CREATED", north, "STAFF_BOOTSTRAPPED", "OIDC_LOGIN");
		}
	}

	@Test
	void branchAndStaffUpdatesRejectMissingAndStaleVersionPreconditions() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branchResponse = admin.mutate("POST", "/api/branches", branch("north", true));
			var branchId = admin.body(branchResponse).get("id").asText();
			var originalBranchEtag = branchResponse.headers().firstValue("ETag").orElseThrow();
			assertThat(admin.body(branchResponse).get("version").asLong()).isZero();
			assertThat(admin.get("/api/branches/" + branchId).headers().firstValue("ETag").orElseThrow())
				.isEqualTo(originalBranchEtag);
			assertThat(admin.mutate("PUT", "/api/branches/" + branchId, branch("north", false)).statusCode())
				.isEqualTo(428);
			var branchUpdate = admin.mutateWithIfMatch("PUT", "/api/branches/" + branchId,
				Map.of("code", "north", "name", "Updated North", "address", "Market Road", "active", true),
				originalBranchEtag);
			assertThat(branchUpdate.statusCode()).isEqualTo(200);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/branches/" + branchId,
				branch("changed", false), originalBranchEtag).statusCode()).isEqualTo(412);
			assertThat(admin.get("/api/branches/" + branchId).body()).contains("\"code\":\"NORTH\"")
				.contains("\"name\":\"Updated North\"")
				.contains("\"active\":true");

			var staffResponse = admin.mutate("POST", "/api/users", Map.of(
				"username", "operator", "fullName", "Operator", "subject", "operator-subject",
				"allBranches", true, "branchIds", Set.of()));
			var staffId = admin.body(staffResponse).get("id").asText();
			var originalStaffEtag = staffResponse.headers().firstValue("ETag").orElseThrow();
			assertThat(admin.body(staffResponse).get("version").asLong()).isZero();
			assertThat(admin.mutate("PUT", "/api/users/" + staffId, Map.of(
				"fullName", "Missing precondition", "active", true, "allBranches", true, "branchIds", Set.of()))
				.statusCode()).isEqualTo(428);
			var staffUpdate = admin.mutateWithIfMatch("PUT", "/api/users/" + staffId, Map.of(
				"fullName", "Updated", "active", true, "allBranches", true, "branchIds", Set.of()),
				originalStaffEtag);
			assertThat(staffUpdate.statusCode()).isEqualTo(200);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + staffId, Map.of(
				"fullName", "Stale", "active", false, "allBranches", false, "branchIds", Set.of()),
				originalStaffEtag).statusCode()).isEqualTo(412);
			assertThat(userEtag(admin, staffId)).isNotEqualTo(originalStaffEtag);
			assertThat(admin.get("/api/users").body()).contains("\"fullName\":\"Updated\"")
				.doesNotContain("\"fullName\":\"Stale\"");
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

	private String userEtag(Browser admin, String id) throws Exception {
		var users = admin.body(admin.get("/api/users"));
		for (var user : users) {
			if (id.equals(user.get("id").asText())) {
				return "\"" + user.get("version").asLong() + "\"";
			}
		}
		throw new AssertionError("Staff member not found: " + id);
	}

	private Map<String, Object> branch(String code, boolean active) {
		return Map.of("code", code, "name", "Branch " + code, "address", "Market Road", "active", active);
	}
}
