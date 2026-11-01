package com.primeroot.platform;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.primeroot.platform.access.StaffIdentityLock;
import com.primeroot.platform.access.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessIntegrationTests extends OidcTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StaffService staff;

	@Autowired
	private StaffIdentityLock identityLock;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM audit_events");
		jdbc.update("DELETE FROM staff_access_requests");
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
				"Main navigation", "Receive stock", "Stock Movement");
			for (var page : List.of("/ui/configurations", "/ui/branches", "/ui/users", "/ui/reports", "/ui/reports/audit")) {
				assertThat(admin.get(page).statusCode()).as(page).isEqualTo(200);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action LIKE 'CATALOGUE_%'",
				Integer.class)).isEqualTo(5);
		}
	}

	@Test
	void concurrentFirstLoginAndProvisioningCannotLeaveAnUnapprovedRequest() throws Exception {
		try (var applicant = browser(); var admin = browser(); var executor = Executors.newFixedThreadPool(3)) {
			admin.adminLogin();
			var lockHeld = new CountDownLatch(1);
			var releaseLock = new CountDownLatch(1);
			var transaction = new TransactionTemplate(transactionManager);
			var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
				identityLock.lock();
				lockHeld.countDown();
				try {
					if (!releaseLock.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to release staff identity lock");
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
			}));
			assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
			var login = executor.submit(() -> {
				try {
					return applicant.login("interleaved-subject", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
				}
				catch (Exception exception) {
					throw new java.util.concurrent.CompletionException(exception);
				}
			});
			var provisioning = executor.submit(() -> {
				try {
					return admin.mutate("POST", "/api/users", Map.of("username", "interleaved",
						"fullName", "Interleaved", "subject", "interleaved-subject",
						"allBranches", true, "branchIds", Set.of()));
				}
				catch (Exception exception) {
					throw new java.util.concurrent.CompletionException(exception);
				}
			});
			try {
				TimeUnit.MILLISECONDS.sleep(250);
				assertThat(login.isDone()).isFalse();
				assertThat(provisioning.isDone()).isFalse();
			}
			finally {
				releaseLock.countDown();
			}
			holder.get(10, TimeUnit.SECONDS);
			login.get(20, TimeUnit.SECONDS);
			assertThat(provisioning.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_requests WHERE subject = ? AND approved = FALSE",
				Integer.class, "interleaved-subject")).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users WHERE subject = ?",
				Integer.class, "interleaved-subject")).isEqualTo(1);
		}
	}

	@Test
	void staffAssignmentWorkspaceRequiresGlobalDirectoryAndSeparateManagementAndBranchPermissions() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = createBranch(admin, "selling");
			assertThat(admin.mutate("POST", "/api/users", Map.of(
				"username", "sales.one", "fullName", "Salesperson One", "subject", "sales-one-subject",
				"allBranches", false, "branchIds", Set.of(branch))).statusCode()).isEqualTo(201);
			var page = admin.get("/ui/users");
			assertThat(page.statusCode()).isEqualTo(200);
			assertThat(page.body()).contains("id=\"staff-form\"", "data-edit-user", "sales.one",
				"Single branch (required for sales)", "Keycloak user ID", branch, "<small>Branch selling</small>");
			try (var reader = browser()) {
				assertThat(reader.login(OidcTestProvider.ADMIN_SUBJECT, List.of("USER_VIEW"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				var readOnly = reader.get("/ui/users");
				assertThat(readOnly.statusCode()).isEqualTo(200);
				assertThat(readOnly.body()).contains("sales.one", "Read-only directory", branch)
					.doesNotContain("id=\"staff-form\"", "data-edit-user");
			}
			try (var manager = browser()) {
				assertThat(manager.login(OidcTestProvider.ADMIN_SUBJECT, List.of("USER_VIEW", "USER_MANAGE"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				var noBranches = manager.get("/ui/users");
				assertThat(noBranches.statusCode()).isEqualTo(200);
				assertThat(noBranches.body()).contains("BRANCH_VIEW is also required")
					.doesNotContain("id=\"staff-form\"", "data-edit-user");
			}
			try (var seller = browser()) {
				assertThat(seller.login("sales-one-subject", List.of("SALE_CREATE", "USER_VIEW", "USER_MANAGE", "BRANCH_VIEW"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				assertThat(seller.get("/ui/users").statusCode()).isEqualTo(403);
			}
		}
	}

	@Test
	void branchWorkspaceSeparatesGlobalCreationScopedEditingAndReadOnlyAccess() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var north = createBranch(admin, "north");
			var south = createBranch(admin, "south");
			var page = admin.get("/ui/branches");
			assertThat(page.statusCode()).isEqualTo(200);
			assertThat(page.body()).contains("data-resource=\"branches\"", "data-create-allowed=\"true\"",
				"Add branch", "data-edit=\"branches\"", north, south);
			try (var reader = browser()) {
				assertThat(reader.login(OidcTestProvider.ADMIN_SUBJECT, List.of("BRANCH_VIEW"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				var readOnly = reader.get("/ui/branches");
				assertThat(readOnly.statusCode()).isEqualTo(200);
				assertThat(readOnly.body()).contains("Read-only branches", "Branch north", "Branch south")
					.doesNotContain("data-resource=\"branches\"", "data-edit=\"branches\"");
			}
			try (var managerWithoutView = browser()) {
				assertThat(managerWithoutView.login(OidcTestProvider.ADMIN_SUBJECT, List.of("BRANCH_MANAGE"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				assertThat(managerWithoutView.get("/ui/branches").statusCode()).isEqualTo(403);
			}
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "north.manager",
				"fullName", "North Manager", "subject", "north-manager-subject",
				"allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(201);
			try (var manager = browser()) {
				assertThat(manager.login("north-manager-subject", List.of("BRANCH_VIEW", "BRANCH_MANAGE"),
					OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
				var scoped = manager.get("/ui/branches");
				assertThat(scoped.statusCode()).isEqualTo(200);
				assertThat(scoped.body()).contains("data-create-allowed=\"false\"", "data-edit=\"branches\"",
					"Creating a new branch requires business-wide", north, "Branch north").doesNotContain(south, "Branch south");
			}
		}
	}

	@Test
	void navigationParentsFollowEffectivePermissionsAndStaffScope() throws Exception {
		record NavigationCase(boolean global, List<String> permissions, List<String> parents) { }
		var cases = List.of(
			new NavigationCase(false, List.of("SALE_CREATE"), List.of("dashboard", "sales", "reports")),
			new NavigationCase(false, List.of("USER_VIEW"), List.of("dashboard")),
			new NavigationCase(true, List.of("USER_VIEW"), List.of("dashboard", "users")),
			new NavigationCase(true, List.of("CATALOGUE_VIEW"), List.of("dashboard", "configurations")),
			new NavigationCase(true, List.of("AUDIT_VIEW"), List.of("dashboard", "reports")),
			new NavigationCase(false, List.of("TRANSFER_VIEW"), List.of("dashboard", "transfers")),
			new NavigationCase(false, List.of("TRANSFER_DISPATCH"), List.of("dashboard")),
			new NavigationCase(true, List.of(), List.of("dashboard")),
			new NavigationCase(true, ALL_PERMISSIONS, List.of("dashboard", "stock", "transfers", "reports", "users", "configurations")));
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = createBranch(admin, "sales");
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "sales.one",
				"fullName", "Salesperson One", "subject", "navigation-sales-subject",
				"allBranches", false, "branchIds", Set.of(branch))).statusCode()).isEqualTo(201);
			for (var scenario : cases) {
				try (var session = browser()) {
					assertThat(session.login(scenario.global() ? OidcTestProvider.ADMIN_SUBJECT : "navigation-sales-subject",
						scenario.permissions(), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
					var page = session.get("/home");
					assertThat(page.statusCode()).isEqualTo(200);
					var parents = java.util.regex.Pattern.compile("id=\"nav-([a-z]+)\"").matcher(page.body())
						.results().map(match -> match.group(1)).toList();
					assertThat(parents).as("global=%s, permissions=%s", scenario.global(), scenario.permissions())
						.containsExactlyElementsOf(scenario.parents());
				}
			}
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
				"not historical balances", "Net sales revenue", "STAFF PERFORMANCE");
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
				.doesNotContain("Staff access", "<h2>Audit activity</h2>", "href=\"/ui/reports/audit\"",
					"/api/users", "/api/audit-events", "2 visible");
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
				.doesNotContain("Staff access", "<h2>Audit activity</h2>", "href=\"/ui/reports/audit\"", "/api/branches");
		}
	}

	@Test
	void landingRoutesStartKeycloakDirectlyAndAuthenticatedStaffGoToTheirWorkspace() throws Exception {
		try (var browser = browser()) {
			for (var path : List.of("/", "/login")) {
				var page = browser.get(path);
				assertThat(page.statusCode()).as(path).isEqualTo(302);
				assertThat(URI.create(page.headers().firstValue("Location").orElseThrow()).getPath())
					.isEqualTo("/oauth2/authorization/keycloak");
			}
			for (var path : List.of("/assets/produce-distribution.svg", "/assets/prime-root-mark.svg")) {
				var image = browser.get(path);
				assertThat(image.statusCode()).as(path).isEqualTo(200);
				assertThat(image.headers().firstValue("Content-Type").orElseThrow()).startsWith("image/svg+xml");
				assertThat(image.body()).contains("<svg");
			}
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
			browser.adminLogin();
			for (var path : List.of("/", "/login")) {
				var page = browser.get(path);
				assertThat(page.statusCode()).as(path).isEqualTo(302);
				assertThat(URI.create(page.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/home");
			}
			assertThat(browser.get("/home").body()).contains("Prime Root", "PRIME ROOT", "| Prime Root")
				.doesNotContain("Produce Platform", "PRODUCE PLATFORM");
		}
	}

	@Test
	void migrationsRetainExternalIdentitySchemaAndEnforceForeignKeys() {
		assertThat(jdbc.queryForList(
			"SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = TRUE AND \"version\" IS NOT NULL", String.class))
			.containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
		assertThat(jdbc.queryForList(
			"SELECT UPPER(table_name) FROM information_schema.tables WHERE table_schema = CURRENT_SCHEMA()", String.class))
			.contains("BRANCHES", "STAFF_USERS", "USER_BRANCHES", "AUDIT_EVENTS", "STAFF_ACCESS_REQUESTS",
				"STAFF_IDENTITY_LOCK")
			.doesNotContain("ROLES", "PERMISSIONS", "ROLE_PERMISSIONS");
		assertThat(jdbc.queryForList(
			"SELECT UPPER(column_name) FROM information_schema.columns WHERE table_schema = CURRENT_SCHEMA() AND LOWER(table_name) = 'staff_users'",
			String.class))
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
	void unknownSubjectsRequestApprovalWithoutGainingStaffAccess() throws Exception {
		try (var browser = browser()) {
			var response = browser.login("unknown-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("Awaiting administrator approval");
			assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(browser.get("/api/users/access-requests").statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(1);
			assertThat(browser.login("unknown-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE).body())
				.contains("Awaiting administrator approval");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_requests WHERE approved = FALSE", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'STAFF_ACCESS_REQUESTED'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void administratorsApproveCapturedIdentitiesWithoutTrustingSubmittedIdentityFields() throws Exception {
		try (var applicant = browser(); var admin = browser(); var reader = browser()) {
			assertThat(applicant.login("pending-sales-subject", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE).body())
				.contains("Awaiting administrator approval");
			admin.adminLogin();
			var branch = createBranch(admin, "pending");
			var pending = admin.body(admin.get("/api/users/access-requests"));
			assertThat(pending.size()).isEqualTo(1);
			var id = pending.get(0).get("id").asText();
			var path = "/api/users/access-requests/" + id + "/approve";
			var approval = Map.of("username", "new.sales", "fullName", "New Salesperson",
				"allBranches", false, "branchIds", Set.of(branch), "subject", OidcTestProvider.ADMIN_SUBJECT,
				"issuer", "https://not-the-trusted-issuer");
			assertThat(applicant.mutate("POST", path, approval).statusCode()).isEqualTo(401);
			assertThat(reader.login(OidcTestProvider.ADMIN_SUBJECT, List.of("USER_VIEW"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(reader.get("/api/users/access-requests").statusCode()).isEqualTo(200);
			assertThat(reader.mutate("POST", path, approval).statusCode()).isEqualTo(403);
			assertThat(admin.send("POST", URI.create("http://localhost:" + port + path), approval, null).statusCode()).isEqualTo(403);
			assertThat(admin.mutate("POST", path, Map.of("username", "new.sales", "fullName", "New Salesperson",
				"allBranches", false, "branchIds", Set.of())).statusCode()).isEqualTo(400);
			assertThat(admin.body(admin.get("/api/users/access-requests")).size()).isEqualTo(1);
			var approved = admin.mutate("POST", path, approval);
			assertThat(approved.statusCode()).withFailMessage(approved.body()).isEqualTo(201);
			assertThat(admin.body(approved).get("subject").asText()).isEqualTo("pending-sales-subject");
			assertThat(admin.body(approved).get("issuer").asText()).isEqualTo(PROVIDER.issuer());
			assertThat(admin.body(admin.get("/api/users/access-requests")).isEmpty()).isTrue();
			assertThat(admin.mutate("POST", path, approval).statusCode()).isEqualTo(409);
			assertThat(applicant.login("pending-sales-subject", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(applicant.get("/api/sales/entry").statusCode()).isEqualTo(200);
			assertThat(applicant.get("/api/users/access-requests").statusCode()).isEqualTo(403);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'STAFF_ACCESS_APPROVED'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void pendingRequestsDoNotBypassRevocationRoleEligibilityOrIssuerBoundaries() throws Exception {
		try (var admin = browser(); var applicant = browser()) {
			admin.adminLogin();
			var disabled = admin.mutate("POST", "/api/users", Map.of("username", "disabled.staff", "fullName", "Disabled",
				"subject", "disabled-subject", "allBranches", true, "branchIds", Set.of()));
			var id = admin.body(disabled).get("id").asText();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + id, Map.of("fullName", "Disabled",
				"active", false, "allBranches", true, "branchIds", Set.of()), userEtag(admin, id)).statusCode()).isEqualTo(200);
			assertThat(applicant.login("disabled-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
			for (var roles : List.of(List.of(), List.of("ADMIN"))) {
				assertThat(applicant.login("no-permissions-subject", roles, OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
			}
			assertThat(applicant.login("bad-roles-subject", "SALE_CREATE", OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(403);
			assertThat(applicant.login("bad-token-subject", ALL_PERMISSIONS, OidcTestProvider.Fault.SIGNATURE).statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_requests", Integer.class)).isZero();
			var foreign = UUID.randomUUID();
			jdbc.update("INSERT INTO staff_access_requests(id, issuer, subject, suggested_username, full_name, approved, created_at) VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
				foreign, "https://another-issuer", "foreign-subject", "foreign.user", "Foreign");
			assertThat(admin.body(admin.get("/api/users/access-requests")).isEmpty()).isTrue();
			assertThat(admin.mutate("POST", "/api/users/access-requests/" + foreign + "/approve", Map.of("username", "foreign.user",
				"fullName", "Foreign", "allBranches", true, "branchIds", Set.of())).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(2);
		}
	}

	@Test
	void concurrentFirstLoginsCreateOnlyOneRequestAndManualApprovalCompletesIt() throws Exception {
		try (var first = browser(); var second = browser(); var admin = browser()) {
			var attempts = List.of(first, second).stream().map(session ->
				java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try {
						return session.login("concurrent-pending-subject", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
					}
					catch (Exception exception) {
						throw new java.util.concurrent.CompletionException(exception);
					}
				})).toList();
			for (var attempt : attempts) {
				assertThat(attempt.get(20, TimeUnit.SECONDS).body()).contains("Awaiting administrator approval");
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_requests", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'STAFF_ACCESS_REQUESTED'", Integer.class)).isEqualTo(1);
			admin.adminLogin();
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", "manual.approval", "fullName", "Approved",
				"subject", "concurrent-pending-subject", "allBranches", true, "branchIds", Set.of())).statusCode()).isEqualTo(201);
			assertThat(admin.body(admin.get("/api/users/access-requests")).isEmpty()).isTrue();
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
	void logoutRequiresCsrfEndsTheSessionAndReturnsThroughProviderLogoutToPrimeRootLogin() throws Exception {
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
			assertThat(URI.create(landing.headers().firstValue("Location").orElseThrow()).getPath())
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
