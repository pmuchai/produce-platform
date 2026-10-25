package com.primeroot.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.primeroot.platform.access.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false",
	"spring.datasource.url=jdbc:h2:mem:inventory-integration-tests;MODE=PostgreSQL"})
class InventoryIntegrationTests extends OidcTestSupport {
	@Autowired private JdbcTemplate jdbc;
	@Autowired private StaffService staff;
	private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

	@BeforeEach
	void reset() {
		for (var table : List.of("audit_events", "stock_movements", "stock_batches", "stock_operations",
				"stock_thresholds", "product_packaging", "products", "product_classifications", "produce_types",
				"user_branches", "staff_users", "branches")) {
			jdbc.update("DELETE FROM " + table);
		}
		staff.bootstrap("admin", "Inventory Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void multiLineReceiptsSnapshotConversionsAndRetriesNeverDuplicateStock() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var packaging = admin.mutate("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", unit("CRATE"), "quantityInBaseUnit", "20.123456"));
			assertThat(packaging.statusCode()).isEqualTo(200);
			var measured = new java.util.HashMap<>(line(product, "BAG", "2", "MEASURED"));
			measured.put("measuredBaseQuantity", "17.345678");
			var request = new java.util.HashMap<>(receipt(branch, List.of(line(product, "CRATE", "2", "FIXED"), measured,
				line(product, "G", "1250", "GRAMS"))));
			request.put("source", "Farm, notes=Manual");
			request.put("notes", "Note");
			var posted = admin.mutate("POST", "/api/inventory/receipts", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			assertThat(admin.mutate("POST", "/api/inventory/receipts", request).statusCode()).isEqualTo(201);
			assertThat(count("stock_operations")).isEqualTo(1);
			assertThat(count("stock_batches")).isEqualTo(3);
			assertThat(count("stock_movements")).isEqualTo(3);
			assertThat(admin.mutateWithIfMatch("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", unit("CRATE"), "quantityInBaseUnit", 25),
				packaging.headers().firstValue("ETag").orElseThrow()).statusCode()).isEqualTo(200);
			var snapshot = admin.body(admin.get("/api/inventory"));
			assertThat(snapshot.get("balances").get(0).get("current").asText()).isEqualTo("58.842590");
			var fixed = find(snapshot.get("batches"), "lotCode", "FIXED");
			assertThat(fixed.get("conversionNumerator").asText()).isEqualTo("20.123456");
			assertThat(fixed.get("receivedQuantity").asText()).isEqualTo("40.246912");
			assertThat(find(snapshot.get("batches"), "lotCode", "MEASURED").get("measured").asBoolean()).isTrue();
			var changed = new java.util.HashMap<>(request);
			changed.put("source", "Farm");
			changed.put("notes", "Manual, notes=Note");
			assertThat(admin.mutate("POST", "/api/inventory/receipts", changed).statusCode()).isEqualTo(409);
			assertThat(count("stock_operations")).isEqualTo(1);
			for (var path : List.of("/home", "/ui/stock-entries", "/ui/stock", "/ui/reports")) {
				var page = admin.get(path);
				assertThat(page.statusCode()).as(path).withFailMessage(page.body()).isEqualTo(200);
			}
			assertThat(admin.get("/ui/stock-entries").body()).contains("40.246912 KG", "Actual measured quantity", "17.345678 KG");
			assertLedgerMatchesBatches();
		}
	}

	@Test
	void invalidReceiptLinesRollbackTheEntirePostingAndQuantitiesAreValidated() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			for (var invalidLine : List.of(line(product, "PIECE", "1", "BAD"),
					line(product, "BAG", "1", "NO_FIXED_FACTOR"), line(product, "G", "0.000001", "ROUNDS_TO_ZERO"),
					line(product, "KG", "-1", "NEGATIVE"))) {
				assertThat(admin.mutate("POST", "/api/inventory/receipts",
					receipt(branch, List.of(line(product, "KG", "10", "VALID"), invalidLine))).statusCode()).isEqualTo(400);
			}
			var expiry = new java.util.HashMap<>(line(product, "KG", "1", "BAD_EXPIRY"));
			expiry.put("expiryDate", today.minusDays(1).toString());
			assertThat(admin.mutate("POST", "/api/inventory/receipts", receipt(branch, List.of(expiry))).statusCode()).isEqualTo(400);
			var request = new java.util.HashMap<>(receipt(branch, List.of(line(product, "KG", "1", "FUTURE"))));
			request.put("receivedDate", today.plusDays(1).toString());
			assertThat(admin.mutate("POST", "/api/inventory/receipts", request).statusCode()).isEqualTo(400);
			assertThat(count("stock_operations")).isZero();
			assertThat(count("stock_batches")).isZero();
			assertThat(count("stock_movements")).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'STOCK_RECEIPT'", Integer.class)).isZero();
		}
	}

	@Test
	void spoilageCorrectionsAndReversalsPreserveAnImmutableNonnegativeLedger() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var received = postReceipt(admin, branch, product, "10");
			var movement = received.get("movements").get(0);
			var batch = movement.get("batchId").asText();
			var spoilRequest = change(branch, batch, "3", "Bruising");
			var spoiled = admin.mutate("POST", "/api/inventory/spoilage", spoilRequest);
			assertThat(spoiled.statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/spoilage", spoilRequest).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/spoilage", change(branch, batch, "-1", "Bad sign")).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/inventory/adjustments", change(branch, batch, "0", "No change")).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/inventory/adjustments", change(branch, batch, "-8", "Too much")).statusCode()).isEqualTo(409);
			var receiptReverse = reverse(branch, movement.get("id").asText(), "Incorrect receipt");
			assertThat(admin.mutate("POST", "/api/inventory/reversals", receiptReverse).statusCode()).isEqualTo(409);
			var spoilId = admin.body(spoiled).get("movements").get(0).get("id").asText();
			var reversal = reverse(branch, spoilId, "Wrong batch selected");
			var reversed = admin.mutate("POST", "/api/inventory/reversals", reversal);
			assertThat(reversed.statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/reversals", reversal).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/reversals", reverse(branch, spoilId, "Again")).statusCode()).isEqualTo(409);
			var reverseId = admin.body(reversed).get("movements").get(0).get("id").asText();
			assertThat(admin.mutate("POST", "/api/inventory/reversals", reverse(branch, reverseId, "Reverse reversal")).statusCode()).isEqualTo(409);
			assertThat(admin.mutate("POST", "/api/inventory/reversals", receiptReverse).statusCode()).isEqualTo(200);
			assertThat(count("stock_movements")).isEqualTo(4);
			assertThat(jdbc.queryForObject("SELECT quantity FROM stock_batches WHERE id = ?", BigDecimal.class, UUID.fromString(batch)))
				.isEqualByComparingTo("0");
			assertThat(admin.mutate("POST", "/api/inventory/adjustments", change(branch, batch, "2.123456", "Count correction")).statusCode()).isEqualTo(200);
			assertLedgerMatchesBatches();
		}
	}

	@Test
	void inventoryPermissionsAndBranchScopeApplyToQueriesMutationsAndUi() throws Exception {
		try (var admin = browser(); var viewer = browser(); var operator = browser()) {
			admin.adminLogin();
			var north = branch(admin, "NORTH");
			var south = branch(admin, "SOUTH");
			var product = product(admin);
			var northMovement = postReceipt(admin, north, product, "10").get("movements").get(0);
			var southMovement = postReceipt(admin, south, product, "20").get("movements").get(0);
			for (var subject : List.of("stock-viewer", "stock-operator")) {
				assertThat(admin.mutate("POST", "/api/users", Map.of("username", subject, "fullName", subject,
					"subject", subject, "allBranches", false, "branchIds", Set.of(north))).statusCode()).isEqualTo(201);
			}
			assertThat(viewer.login("stock-viewer", List.of("STOCK_VIEW"), OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(viewer.get("/api/catalogue").statusCode()).isEqualTo(403);
			assertThat(viewer.get("/api/inventory").body()).contains("NORTH").doesNotContain("SOUTH");
			assertThat(viewer.get("/api/inventory?branchId=" + south).statusCode()).isEqualTo(403);
			var viewPage = viewer.get("/ui/stock-entries");
			assertThat(viewPage.statusCode()).isEqualTo(200);
			assertThat(viewPage.body()).doesNotContain("data-stock=\"receipts\"", "data-stock=\"adjustments\"");
			assertThat(viewer.mutate("POST", "/api/inventory/receipts",
				receipt(north, List.of(line(product, "KG", "1", "FORBIDDEN")))).statusCode()).isEqualTo(403);
			assertThat(operator.login("stock-operator", List.of("STOCK_VIEW", "STOCK_RECEIVE", "STOCK_SPOILAGE", "STOCK_ADJUST"),
				OidcTestProvider.Fault.NONE).statusCode()).isEqualTo(200);
			assertThat(operator.mutate("POST", "/api/inventory/receipts",
				receipt(south, List.of(line(product, "KG", "1", "FORBIDDEN")))).statusCode()).isEqualTo(403);
			assertThat(operator.mutate("POST", "/api/inventory/spoilage",
				change(north, southMovement.get("batchId").asText(), "1", "Wrong branch batch")).statusCode()).isEqualTo(404);
			assertThat(operator.mutate("POST", "/api/inventory/reversals",
				reverse(north, southMovement.get("id").asText(), "Wrong branch movement")).statusCode()).isEqualTo(404);
			var uri = java.net.URI.create("http://localhost:" + port + "/api/inventory/spoilage");
			assertThat(operator.send("POST", uri, change(north, northMovement.get("batchId").asText(), "1", "Missing CSRF"), null)
				.statusCode()).isEqualTo(403);
			assertThat(operator.mutate("POST", "/api/inventory/spoilage",
				change(north, northMovement.get("batchId").asText(), "1", "Damaged")).statusCode()).isEqualTo(200);
		}
	}

	@Test
	void competingDeductionsAndDuplicateReceiptSubmissionsAreSerialized() throws Exception {
		try (var admin = browser(); var other = browser(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			admin.adminLogin();
			other.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var batch = postReceipt(admin, branch, product, "10").get("movements").get(0).get("batchId").asText();
			var start = new CountDownLatch(1);
			var first = executor.submit(() -> { start.await(); return admin.mutate("POST", "/api/inventory/spoilage", change(branch, batch, "7", "First")).statusCode(); });
			var second = executor.submit(() -> { start.await(); return other.mutate("POST", "/api/inventory/spoilage", change(branch, batch, "7", "Second")).statusCode(); });
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
			assertThat(jdbc.queryForObject("SELECT quantity FROM stock_batches WHERE id = ?", BigDecimal.class, UUID.fromString(batch)))
				.isEqualByComparingTo("3");
			var request = receipt(branch, List.of(line(product, "KG", "5", "RETRY")));
			var retryStart = new CountDownLatch(1);
			var retry1 = executor.submit(() -> { retryStart.await(); return admin.mutate("POST", "/api/inventory/receipts", request).statusCode(); });
			var retry2 = executor.submit(() -> { retryStart.await(); return other.mutate("POST", "/api/inventory/receipts", request).statusCode(); });
			retryStart.countDown();
			assertThat(List.of(retry1.get(20, TimeUnit.SECONDS), retry2.get(20, TimeUnit.SECONDS))).containsOnly(201);
			assertThat(count("stock_batches")).isEqualTo(2);
			assertThat(count("stock_operations")).isEqualTo(3);
			assertLedgerMatchesBatches();
		}
	}

	@Test
	void dashboardSeparatesCurrentStockFromAsOfBalancesAndComparesPostingIntervals() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var previous = postReceipt(admin, branch, product, "20");
			movePosting(previous.get("id").asText(), today.minusDays(1), 0);
			var previousSpoilage = admin.body(admin.mutate("POST", "/api/inventory/spoilage",
				change(branch, previous.get("movements").get(0).get("batchId").asText(), "5", "Yesterday's damage")));
			movePosting(previousSpoilage.get("id").asText(), today.minusDays(1), 60);
			var current = postReceipt(admin, branch, product, "10");
			movePosting(current.get("id").asText(), today, 0);
			var spoiled = admin.body(admin.mutate("POST", "/api/inventory/spoilage",
				change(branch, current.get("movements").get(0).get("batchId").asText(), "2", "Today's damage")));
			movePosting(spoiled.get("id").asText(), today, 60);
			var balance = admin.body(admin.get("/api/inventory?start=" + today + "&end=" + today)).get("balances").get(0);
			assertThat(balance.get("current").asText()).isEqualTo("23.000000");
			assertThat(balance.get("receipts").asText()).isEqualTo("10.000000");
			assertThat(balance.get("previousReceipts").asText()).isEqualTo("20.000000");
			assertThat(balance.get("spoilage").asText()).isEqualTo("2.000000");
			assertThat(balance.get("previousSpoilage").asText()).isEqualTo("5.000000");
			var historical = admin.body(admin.get("/api/inventory?start=" + today.minusDays(1) + "&end=" + today.minusDays(1)));
			assertThat(historical.get("balances").get(0).get("asOf").asText()).isEqualTo("15.000000");
			assertThat(historical.get("balances").get(0).get("current").asText()).isEqualTo("23.000000");
			assertThat(historical.get("movementCount").asInt()).isEqualTo(2);
			assertThat(admin.get("/api/inventory?start=" + today).statusCode()).isEqualTo(400);
			assertThat(admin.get("/api/inventory?page=-1").statusCode()).isEqualTo(400);
			assertThat(admin.get("/ui/stock?start=" + today + "&end=" + today + "&branchId=" + branch).body()).contains("23.000000 KG");
			assertLedgerMatchesBatches();
		}
	}

	@Test
	void thresholdsUseVersionPreconditionsAndExpiryAlertsOnlyIncludeAvailableBatches() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var threshold = Map.of("branchId", branch, "productId", product, "minimumQuantity", "10", "expectedVersion", -1);
			assertThat(admin.mutate("PUT", "/api/inventory/thresholds", threshold).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("PUT", "/api/inventory/thresholds", threshold).statusCode()).isEqualTo(412);
			var snapshot = admin.body(admin.get("/api/inventory"));
			assertThat(snapshot.get("lowStockCount").asInt()).isEqualTo(1);
			assertThat(snapshot.get("balances").get(0).get("current").asText()).isEqualTo("0");
			var line = new java.util.HashMap<>(line(product, "KG", "12", "EXPIRING"));
			line.put("expiryDate", today.plusDays(3).toString());
			var posted = admin.body(admin.mutate("POST", "/api/inventory/receipts", receipt(branch, List.of(line))));
			var batch = posted.get("movements").get(0).get("batchId").asText();
			snapshot = admin.body(admin.get("/api/inventory"));
			assertThat(snapshot.get("nearExpiryCount").asInt()).isEqualTo(1);
			assertThat(snapshot.get("lowStockCount").asInt()).isZero();
			assertThat(admin.mutate("PUT", "/api/inventory/thresholds", Map.of("branchId", branch, "productId", product,
				"minimumQuantity", "15", "expectedVersion", 0)).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("PUT", "/api/inventory/thresholds", Map.of("branchId", branch, "productId", product,
				"minimumQuantity", "0", "expectedVersion", 0)).statusCode()).isEqualTo(412);
			assertThat(admin.mutate("POST", "/api/inventory/spoilage", change(branch, batch, "12", "Expired")).statusCode()).isEqualTo(200);
			assertThat(admin.body(admin.get("/api/inventory")).get("nearExpiryCount").asInt()).isZero();
			assertThat(admin.get("/home").body()).contains("1 low-stock alerts", "0 batches expired");
		}
	}

	@Test
	void inactiveDefinitionsBlockReceivingButDoNotStrandExistingStock() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			var received = postReceipt(admin, branch, product, "2");
			var catalogue = admin.body(admin.get("/api/catalogue"));
			var type = catalogue.get("types").get(0).get("id").asText();
			var version = find(catalogue.get("products"), "id", product).get("version").asLong();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/catalogue/products/" + product, Map.of("code", "TOMATO_KG",
				"name", "Tomatoes", "active", false, "produceTypeId", type, "baseUnitId", unit("KG")),
				"\"" + version + "\"").statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/receipts",
				receipt(branch, List.of(line(product, "KG", "1", "INACTIVE")))).statusCode()).isEqualTo(400);
			var etag = admin.get("/api/branches/" + branch).headers().firstValue("ETag").orElseThrow();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/branches/" + branch,
				Map.of("code", "MAIN", "name", "MAIN", "active", false), etag).statusCode()).isEqualTo(200);
			var batch = received.get("movements").get(0).get("batchId").asText();
			assertThat(admin.mutate("POST", "/api/inventory/spoilage",
				change(branch, batch, "1", "Dispose of discontinued stock")).statusCode()).isEqualTo(200);
			assertThat(admin.body(admin.get("/api/inventory")).get("balances").get(0).get("current").asText())
				.isEqualTo("1.000000");
			assertLedgerMatchesBatches();
		}
	}

	@Test
	void convertedAndRunningBatchQuantitiesCannotOverflowStorage() throws Exception {
		try (var admin = browser()) {
			admin.adminLogin();
			var branch = branch(admin, "MAIN");
			var product = product(admin);
			assertThat(admin.mutate("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", unit("CRATE"), "quantityInBaseUnit", "2")).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/inventory/receipts", receipt(branch,
				List.of(line(product, "CRATE", "9999999999999.999999", "OVERFLOW")))).statusCode()).isEqualTo(400);
			var received = postReceipt(admin, branch, product, "9999999999999.999999");
			var batch = received.get("movements").get(0).get("batchId").asText();
			assertThat(admin.mutate("POST", "/api/inventory/adjustments",
				change(branch, batch, "0.000001", "Would overflow")).statusCode()).isEqualTo(400);
			assertThat(count("stock_operations")).isEqualTo(1);
			assertLedgerMatchesBatches();
		}
	}

	private String branch(Browser admin, String code) throws Exception {
		var response = admin.mutate("POST", "/api/branches", Map.of("code", code, "name", code, "active", true));
		assertThat(response.statusCode()).isEqualTo(201);
		return admin.body(response).get("id").asText();
	}

	private String product(Browser admin) throws Exception {
		var type = admin.body(admin.mutate("POST", "/api/catalogue/types",
			Map.of("code", "TOMATO", "name", "Tomatoes", "active", true))).get("id").asText();
		var response = admin.mutate("POST", "/api/catalogue/products", Map.of("code", "TOMATO_KG",
			"name", "Tomatoes", "active", true, "produceTypeId", type, "baseUnitId", unit("KG")));
		assertThat(response.statusCode()).isEqualTo(201);
		return admin.body(response).get("id").asText();
	}

	private String unit(String code) {
		return jdbc.queryForObject("SELECT id FROM units_of_measure WHERE code = ?", UUID.class, code).toString();
	}

	private Map<String, Object> line(String product, String unit, String quantity, String lot) {
		return Map.of("productId", product, "unitId", unit(unit), "quantity", quantity, "lotCode", lot);
	}

	private Map<String, Object> receipt(String branch, List<Map<String, Object>> lines) {
		return Map.of("requestId", UUID.randomUUID().toString(), "branchId", branch, "receivedDate", today.toString(),
			"source", "Farm delivery", "lines", lines);
	}

	private JsonNode postReceipt(Browser admin, String branch, String product, String quantity) throws Exception {
		var response = admin.mutate("POST", "/api/inventory/receipts", receipt(branch, List.of(line(product, "KG", quantity, "LOT"))));
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
		return admin.body(response);
	}

	private Map<String, Object> change(String branch, String batch, String quantity, String reason) {
		return Map.of("requestId", UUID.randomUUID().toString(), "branchId", branch, "batchId", batch, "quantity", quantity, "reason", reason);
	}

	private Map<String, Object> reverse(String branch, String movement, String reason) {
		return Map.of("requestId", UUID.randomUUID().toString(), "branchId", branch, "movementId", movement, "reason", reason);
	}

	private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

	private JsonNode find(JsonNode rows, String field, String value) {
		for (var row : rows) if (row.get(field).asText().equals(value)) return row;
		throw new AssertionError("Missing record " + value);
	}

	private void movePosting(String id, LocalDate date, long seconds) {
		jdbc.update("UPDATE stock_operations SET posted_at = ? WHERE id = ?",
			date.atStartOfDay().plusSeconds(seconds).atOffset(ZoneOffset.UTC), UUID.fromString(id));
	}

	private void assertLedgerMatchesBatches() {
		assertThat(jdbc.queryForObject("""
			SELECT COUNT(*) FROM stock_batches b WHERE b.quantity < 0 OR b.quantity <>
			(SELECT COALESCE(SUM(m.quantity), 0) FROM stock_movements m WHERE m.batch_id = b.id)
			""", Integer.class)).isZero();
	}
}
