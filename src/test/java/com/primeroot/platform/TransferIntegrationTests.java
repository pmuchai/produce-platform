package com.primeroot.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
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
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false", "platform.inventory.currency=KES",
	"spring.datasource.url=jdbc:h2:mem:transfer-integration-tests;MODE=PostgreSQL"})
class TransferIntegrationTests extends OidcTestSupport {
	@Autowired private StaffService staff;
	@Autowired private JdbcTemplate jdbc;
	private String source;
	private String destination;
	private String unrelated;
	private String product;
	private String grade;
	private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
	private final List<String> transferPermissions = List.of("TRANSFER_VIEW", "TRANSFER_DISPATCH", "TRANSFER_RECEIVE");

	@BeforeEach
	void reset() {
		for (var table : List.of("audit_events", "sale_date_corrections", "transfer_allocations", "stock_transfers", "sale_allocations", "sale_lines",
				"sales", "stock_movements", "stock_batches", "stock_operations", "stock_thresholds", "product_packaging",
				"products", "product_classifications", "produce_types", "user_branches", "staff_users", "branches")) {
			jdbc.update("DELETE FROM " + table);
		}
		staff.bootstrap("admin", "Transfer Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void mixedGradeDeliveryKeepsSeparateGradesAndCostsThroughTransferArrival() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			var type = jdbc.queryForObject("SELECT produce_type_id FROM products WHERE id = ?", UUID.class, UUID.fromString(product));
			var small = admin.body(admin.mutate("POST", "/api/catalogue/classifications", Map.of("code", "SMALL", "name", "Small",
				"active", true, "produceTypeId", type, "kind", "GRADE"))).get("id").asText();
			var receipt = Map.of("requestId", UUID.randomUUID(), "branchId", source, "receivedDate", today.toString(),
				"source", "Mixed delivery", "currency", "KES", "lines", List.of(
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "5", "purchaseUnitCost", "2", "lotCode", "SAME-LOT", "gradeId", small),
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "10", "purchaseUnitCost", "3", "lotCode", "SAME-LOT", "gradeId", grade)));
			assertThat(admin.mutate("POST", "/api/inventory/receipts", receipt).statusCode()).isEqualTo(201);
			var dispatched = admin.body(admin.mutate("POST", "/api/transfers", dispatch("15")));
			assertThat(dispatched.get("allocations").size()).isEqualTo(2);
			var transferred = admin.mutate("POST", "/api/transfers/" + dispatched.get("transfer").get("id").asText() + "/receive",
				receipt(arrivals(dispatched)));
			assertThat(transferred.statusCode()).withFailMessage(transferred.body()).isEqualTo(200);
			var batches = admin.body(admin.get("/api/inventory/batches?branchId=" + destination)).get("batches");
			assertThat(batches.size()).isEqualTo(2);
			var grades = new HashSet<String>();
			for (var batch : batches) {
				grades.add(batch.get("grade").asText());
				assertThat(batch.get("lotCode").asText()).isEqualTo("SAME-LOT");
				assertThat(batch.get("gradeId").asText()).isIn(small, grade);
			}
			assertThat(grades).containsExactlyInAnyOrder("Small", "Grade A");
			assertThat(jdbc.queryForObject("SELECT SUM(remaining_cost) FROM stock_batches WHERE branch_id = ?", BigDecimal.class,
				UUID.fromString(destination))).isEqualByComparingTo("40");
			assertLedgers();
		}
	}

	@Test
	void fefoDispatchThenFinalArrivalPreservesTraceAndConservesCostsWithoutSalesOrGenericReversal() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			receive(admin, "10", "2.333333", "EARLY", today.plusDays(1));
			receive(admin, "10", "3", "LATE", today.plusDays(10));
			receive(admin, "10", "9", "UNDATED", null);
			receive(admin, "10", "1", "EXPIRED", today.minusDays(1));
			var request = dispatch("15");
			var posted = admin.mutate("POST", "/api/transfers", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			var detail = admin.body(posted);
			var id = detail.get("transfer").get("id").asText();
			assertThat(detail.get("transfer").get("status").asText()).isEqualTo("IN_TRANSIT");
			assertThat(detail.get("allocations").size()).isEqualTo(2);
			assertBatch(source, "EARLY", "0", "0");
			assertBatch(source, "LATE", "5", "15");
			assertBatch(source, "UNDATED", "10", "90");
			assertBatch(source, "EXPIRED", "10", "10");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_batches WHERE branch_id = ?", Integer.class, UUID.fromString(destination))).isZero();
			assertThat(admin.body(admin.get("/api/transfers?status=IN_TRANSIT")).get("totalElements").asInt()).isEqualTo(1);
			assertThat(admin.mutate("POST", "/api/transfers", request).statusCode()).isEqualTo(201);
			request.put("lines", List.of(line("16")));
			assertThat(admin.mutate("POST", "/api/transfers", request).statusCode()).isEqualTo(409);
			var arrivals = new ArrayList<Map<String, Object>>();
			for (var allocation : detail.get("allocations")) {
				boolean early = allocation.get("lotCode").asText().equals("EARLY");
				arrivals.add(arrival(allocation, early ? "7" : "5", early ? "2" : "0", early ? "1" : "0", "Short loaded and crushed"));
			}
			var receipt = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID(), "allocations", arrivals));
			var received = admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt);
			assertThat(received.statusCode()).withFailMessage(received.body()).isEqualTo(200);
			assertThat(admin.body(received).get("transfer").get("status").asText()).isEqualTo("RECEIVED");
			assertBatch(destination, "EARLY", "7", "16.33");
			assertBatch(destination, "LATE", "5", "15");
			assertThat(jdbc.queryForObject("SELECT SUM(loss_cost) FROM transfer_allocations", BigDecimal.class)).isEqualByComparingTo("7.00");
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(200);
			receipt.put("notes", "Conflicting replay");
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(409);
			receipt.put("requestId", UUID.randomUUID());
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(409);
			for (var allocation : admin.body(received).get("allocations")) {
				var row = jdbc.queryForMap("SELECT * FROM stock_batches WHERE id = ?", UUID.fromString(allocation.get("destinationBatchId").asText()));
				assertThat(row.get("source_batch_id").toString()).isEqualTo(allocation.get("sourceBatchId").asText());
				assertThat(row.get("product_id").toString()).isEqualTo(product);
				assertThat(row.get("received_date").toString()).isEqualTo(today.minusDays(10).toString());
				assertThat(row.get("expiry_date").toString()).isEqualTo(allocation.get("expiryDate").asText());
				assertThat(allocation.get("produceType").asText()).isEqualTo("Onions");
				assertThat(allocation.get("grade").asText()).isEqualTo("Grade A");
			}
			for (var movement : jdbc.queryForList("SELECT id, batch_id FROM stock_movements WHERE kind IN ('TRANSFER_OUT','TRANSFER_IN')")) {
				var branch = jdbc.queryForObject("SELECT branch_id FROM stock_batches WHERE id = ?", UUID.class, movement.get("batch_id"));
				assertThat(admin.mutate("POST", "/api/inventory/reversals", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
					"movementId", movement.get("id"), "reason", "Must not reverse")).statusCode()).isEqualTo(409);
			}
			assertThat(admin.body(admin.get("/api/transfers?status=IN_TRANSIT")).get("totalElements").asInt()).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_events", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'TRANSFER_DISCREPANCY'", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'TRANSFER_RECEIVED'", Integer.class)).isEqualTo(1);
			assertThat(admin.get("/ui/transfers").body()).contains("Dispatch stock", "Transfer history", "Stock transfers");
			assertThat(admin.get("/ui/transfers/" + id).body()).contains("Grade A", "16.330000").doesNotContain("id=\"transfer-receive\"");
			assertLedgers();
		}
	}

	@Test
	void fullLossAndUnknownLegacyCostsAreAccountedWithoutInventingDestinationStockOrValue() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			receive(admin, "3", "0.333333", "LOSS", null);
			var fullLoss = admin.body(admin.mutate("POST", "/api/transfers", dispatch("3")));
			var id = fullLoss.get("transfer").get("id").asText();
			var item = fullLoss.get("allocations").get(0);
			var invalid = arrival(item, "0", "1", "2", "");
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(List.of(invalid))).statusCode()).isEqualTo(400);
			var received = admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(List.of(arrival(item, "0", "1", "2", "Entire load lost"))));
			assertThat(received.statusCode()).withFailMessage(received.body()).isEqualTo(200);
			var loss = admin.body(received).get("allocations").get(0);
			assertThat(loss.get("destinationBatchId").isNull()).isTrue();
			assertThat(new BigDecimal(loss.get("receivedCost").asText())).isEqualByComparingTo("0");
			assertThat(new BigDecimal(loss.get("lossCost").asText())).isEqualByComparingTo("1");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE kind = 'TRANSFER_IN'", Integer.class)).isZero();
			receive(admin, "4", "2", "UNKNOWN", null);
			jdbc.update("UPDATE stock_batches SET currency = NULL, purchase_unit_cost = NULL, received_cost = NULL, remaining_cost = NULL WHERE lot_code = 'UNKNOWN'");
			jdbc.update("UPDATE stock_movements SET cost_change = NULL WHERE batch_id IN (SELECT id FROM stock_batches WHERE lot_code = 'UNKNOWN')");
			var dispatched = admin.body(admin.mutate("POST", "/api/transfers", dispatch("4")));
			var unknown = dispatched.get("allocations").get(0);
			var arrival = admin.mutate("POST", "/api/transfers/" + dispatched.get("transfer").get("id").asText() + "/receive",
				receipt(List.of(arrival(unknown, "3", "1", "0", "Short loaded"))));
			assertThat(arrival.statusCode()).withFailMessage(arrival.body()).isEqualTo(200);
			assertThat(admin.body(arrival).get("allocations").get(0).get("receivedCost").isNull()).isTrue();
			assertThat(jdbc.queryForObject("SELECT remaining_cost FROM stock_batches WHERE branch_id = ?", BigDecimal.class, UUID.fromString(destination))).isNull();
			receive(admin, "2", "2", "CARRYING_UNKNOWN", null);
			jdbc.update("UPDATE stock_batches SET remaining_cost = NULL WHERE lot_code = 'CARRYING_UNKNOWN'");
			var carryingUnknown = admin.body(admin.mutate("POST", "/api/transfers", dispatch("2")));
			var unknownArrival = admin.mutate("POST", "/api/transfers/" + carryingUnknown.get("transfer").get("id").asText()
				+ "/receive", receipt(arrivals(carryingUnknown)));
			assertThat(unknownArrival.statusCode()).withFailMessage(unknownArrival.body()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT currency FROM stock_batches WHERE lot_code = 'CARRYING_UNKNOWN' AND branch_id = ?",
				String.class, UUID.fromString(destination))).isNull();
			assertLedgers();
		}
	}

	@Test
	void receiptPersistsDerivedBaseUnitCostBeyondPreviousPrecision() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			var packaging = admin.mutate("PUT", "/api/catalogue/packaging",
				Map.of("productId", product, "unitId", unit("CRATE"), "quantityInBaseUnit", "0.000001"));
			assertThat(packaging.statusCode()).withFailMessage(packaging.body()).isEqualTo(200);
			var receiptLine = Map.of("productId", product, "unitId", unit("CRATE"), "quantity", "1",
				"purchaseUnitCost", "10000000", "lotCode", "HIGH_UNIT_COST");
			var receiptResponse = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", UUID.randomUUID(),
				"branchId", source, "receivedDate", today.toString(), "source", "Farm", "currency", "KES",
				"lines", List.of(receiptLine)));
			assertThat(receiptResponse.statusCode()).withFailMessage(receiptResponse.body()).isEqualTo(201);
			var dispatched = admin.body(admin.mutate("POST", "/api/transfers", dispatch("0.000001")));
			var received = admin.mutate("POST", "/api/transfers/" + dispatched.get("transfer").get("id").asText()
				+ "/receive", receipt(arrivals(dispatched)));
			assertThat(received.statusCode()).withFailMessage(received.body()).isEqualTo(200);
			var purchaseUnitCost = jdbc.queryForObject(
				"SELECT purchase_unit_cost FROM stock_batches WHERE branch_id = ?",
				BigDecimal.class, UUID.fromString(destination));
			assertThat(purchaseUnitCost).isEqualByComparingTo("10000000000000");
		}
	}

	@Test
	void insufficientStockInvalidRoutesAndIncompleteReceiptsRollBackAtomically() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			receive(admin, "5", "10", "STOCK", null);
			assertThat(admin.mutate("POST", "/api/transfers", dispatch("6")).statusCode()).isEqualTo(409);
			var multi = dispatch("2");
			multi.put("lines", List.of(line("3"), line("3")));
			assertThat(admin.mutate("POST", "/api/transfers", multi).statusCode()).isEqualTo(409);
			var same = dispatch("1");
			same.put("destinationId", source);
			assertThat(admin.mutate("POST", "/api/transfers", same).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/transfers", dispatch("0")).statusCode()).isEqualTo(400);
			var nullLine = dispatch("1");
			nullLine.put("lines", Arrays.asList((Object) null));
			assertThat(admin.mutate("POST", "/api/transfers", nullLine).statusCode()).isEqualTo(400);
			jdbc.update("UPDATE branches SET active = FALSE WHERE id = ?", UUID.fromString(destination));
			assertThat(admin.mutate("POST", "/api/transfers", dispatch("1")).statusCode()).isEqualTo(409);
			jdbc.update("UPDATE branches SET active = TRUE WHERE id = ?", UUID.fromString(destination));
			jdbc.update("UPDATE branches SET active = FALSE WHERE id = ?", UUID.fromString(source));
			assertThat(admin.mutate("POST", "/api/transfers", dispatch("1")).statusCode()).isEqualTo(409);
			jdbc.update("UPDATE branches SET active = TRUE WHERE id = ?", UUID.fromString(source));
			assertBatch(source, "STOCK", "5", "50");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfers", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations WHERE kind = 'TRANSFER_OUT'", Integer.class)).isZero();
			receive(admin, "5", "10", "SECOND", today.plusDays(1));
			var detail = admin.body(admin.mutate("POST", "/api/transfers", dispatch("7")));
			var id = detail.get("transfer").get("id").asText();
			var a = detail.get("allocations").get(0);
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(List.of(arrival(a, "1", "0", "0", "")))).statusCode()).isEqualTo(400);
			var valid = arrivals(detail);
			var duplicate = new ArrayList<>(valid);
			duplicate.set(1, duplicate.get(0));
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(duplicate)).statusCode()).isEqualTo(400);
			var wrong = new ArrayList<>(valid);
			wrong.set(0, arrival(a, "0", "0", "0", ""));
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(wrong)).statusCode()).isEqualTo(400);
			wrong.set(0, arrival(a, "-1", a.get("quantity").asText(), "1", "Invalid negative arrival"));
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(wrong)).statusCode()).isEqualTo(400);
			jdbc.update("UPDATE branches SET active = FALSE WHERE id = ?", UUID.fromString(destination));
			assertThat(admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt(valid)).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations WHERE kind = 'TRANSFER_IN'", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT status FROM stock_transfers", String.class)).isEqualTo("IN_TRANSIT");
			assertLedgers();
		}
	}

	@Test
	void permissionsSourceDestinationScopeAndUiHideCostsAndUnrelatedHistory() throws Exception {
		try (var admin = browser(); var sender = browser(); var receiver = browser(); var outsider = browser(); var noPermissions = browser()) {
			fixture(admin);
			receive(admin, "10", "12", "STOCK", null);
			sender.login("transfer-source", transferPermissions, OidcTestProvider.Fault.NONE);
			receiver.login("transfer-destination", transferPermissions, OidcTestProvider.Fault.NONE);
			outsider.login("transfer-unrelated", transferPermissions, OidcTestProvider.Fault.NONE);
			noPermissions.login("transfer-source", List.of("STOCK_VIEW", "STOCK_ADJUST", "STOCK_RECEIVE"), OidcTestProvider.Fault.NONE);
			assertThat(noPermissions.get("/api/transfers").statusCode()).isEqualTo(403);
			assertThat(noPermissions.get("/api/transfers/entry").statusCode()).isEqualTo(403);
			assertThat(noPermissions.mutate("POST", "/api/transfers", dispatch("1")).statusCode()).isEqualTo(403);
			assertThat(receiver.mutate("POST", "/api/transfers", dispatch("1")).statusCode()).isEqualTo(403);
			var request = dispatch("5");
			var posted = sender.mutate("POST", "/api/transfers", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			var detail = sender.body(posted);
			var id = detail.get("transfer").get("id").asText();
			assertThat(detail.get("allocations").get(0).get("cost").isNull()).isTrue();
			assertThat(detail.get("allocations").get(0).get("purchaseUnitCost").isNull()).isTrue();
			assertThat(detail.get("canReceive").asBoolean()).isFalse();
			assertThat(receiver.body(receiver.get("/api/transfers/" + id)).get("canReceive").asBoolean()).isTrue();
			assertThat(sender.body(sender.get("/api/transfers")).get("totalElements").asInt()).isEqualTo(1);
			assertThat(receiver.body(receiver.get("/api/transfers")).get("totalElements").asInt()).isEqualTo(1);
			assertThat(outsider.body(outsider.get("/api/transfers")).get("totalElements").asInt()).isZero();
			assertThat(outsider.get("/api/transfers/" + id).statusCode()).isEqualTo(403);
			assertThat(noPermissions.get("/api/transfers/" + id).statusCode()).isEqualTo(403);
			assertThat(outsider.get("/ui/transfers/" + id).statusCode()).isEqualTo(403);
			assertThat(receiver.mutate("POST", "/api/transfers", request).statusCode()).isEqualTo(403);
			var receipt = receipt(arrivals(detail));
			assertThat(sender.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(403);
			assertThat(noPermissions.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(403);
			assertThat(receiver.get("/ui/transfers/" + id).body()).contains("id=\"transfer-receive\"").doesNotContain("Carrying cost / arrived / lost");
			var arrival = receiver.mutate("POST", "/api/transfers/" + id + "/receive", receipt);
			assertThat(arrival.statusCode()).withFailMessage(arrival.body()).isEqualTo(200);
			assertThat(receiver.body(arrival).get("allocations").get(0).get("receivedCost").isNull()).isTrue();
			assertThat(new BigDecimal(admin.body(admin.get("/api/transfers/" + id)).get("allocations").get(0).get("receivedCost").asText())).isEqualByComparingTo("60");
			noPermissions.login("transfer-peer", transferPermissions, OidcTestProvider.Fault.NONE);
			assertThat(noPermissions.mutate("POST", "/api/transfers", request).statusCode()).isEqualTo(409);
			receiver.login("transfer-destination", List.of("TRANSFER_VIEW"), OidcTestProvider.Fault.NONE);
			assertThat(receiver.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode()).isEqualTo(403);
			sender.login("transfer-source", List.of("TRANSFER_VIEW"), OidcTestProvider.Fault.NONE);
			assertThat(sender.mutate("POST", "/api/transfers", dispatch("1")).statusCode()).isEqualTo(403);
			assertLedgers();
		}
	}

	@Test
	void concurrentDispatchAndReceiveRetriesDoNotDoublePostAndCompetingSalesCannotOversell() throws Exception {
		try (var admin = browser(); var peer = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "10", "10", "STOCK", null);
			peer.adminLogin();
			var request = dispatch("6");
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/transfers", request).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return peer.mutate("POST", "/api/transfers", request).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsOnly(201);
			}
			assertBatch(source, "STOCK", "4", "40");
			var id = jdbc.queryForObject("SELECT id FROM stock_transfers", UUID.class).toString();
			var detail = admin.body(admin.get("/api/transfers/" + id));
			var receipt = receipt(arrivals(detail));
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return peer.mutate("POST", "/api/transfers/" + id + "/receive", receipt).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsOnly(200);
			}
			assertBatch(destination, "STOCK", "6", "60");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations WHERE kind = 'TRANSFER_IN'", Integer.class)).isEqualTo(1);
			seller.login("transfer-source", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var sale = Map.of("requestId", UUID.randomUUID(), "paymentMethod", "CASH",
				"lines", List.of(Map.of("productId", product, "unitId", unit("KG"), "quantity", "3", "unitPrice", "20")));
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/transfers", dispatch("3")).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return seller.mutate("POST", "/api/sales", sale).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
			}
			assertBatch(source, "STOCK", "1", "10");
			assertLedgers();
		}
	}

	@Test
	void classificationsSurviveCatalogueEditsAndRepeatedTransfersAndArrivalStockCanBeSoldAndVoided() throws Exception {
		try (var admin = browser(); var destinationSeller = browser(); var receiver = browser()) {
			fixture(admin);
			receive(admin, "10", "2", "CHAIN", today.plusDays(10));
			jdbc.update("UPDATE products SET name = 'Renamed onions' WHERE id = ?", UUID.fromString(product));
			jdbc.update("UPDATE product_classifications SET name = 'Renamed grade', active = FALSE WHERE id = ?", UUID.fromString(grade));
			var dispatched = admin.body(admin.mutate("POST", "/api/transfers", dispatch("5")));
			var id = dispatched.get("transfer").get("id").asText();
			assertThat(dispatched.get("allocations").get(0).get("product").asText()).isEqualTo("Red onions");
			assertThat(dispatched.get("allocations").get(0).get("grade").asText()).isEqualTo("Grade A");
			jdbc.update("UPDATE branches SET active = FALSE WHERE id = ?", UUID.fromString(source));
			receiver.login("transfer-destination", List.of("TRANSFER_VIEW", "TRANSFER_RECEIVE"), OidcTestProvider.Fault.NONE);
			var arrived = receiver.mutate("POST", "/api/transfers/" + id + "/receive", receipt(arrivals(dispatched)));
			assertThat(arrived.statusCode()).withFailMessage(arrived.body()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT grade_snapshot FROM stock_batches WHERE branch_id = ?", String.class, UUID.fromString(destination)))
				.isEqualTo("Grade A");
			var destinationBatch = receiver.body(arrived).get("allocations").get(0).get("destinationBatchId").asText();
			var secondRequest = dispatch("2");
			secondRequest.put("sourceId", destination);
			secondRequest.put("destinationId", unrelated);
			var second = admin.body(admin.mutate("POST", "/api/transfers", secondRequest));
			assertThat(second.get("allocations").get(0).get("grade").asText()).isEqualTo("Grade A");
			assertThat(second.get("allocations").get(0).get("sourceBatchId").asText()).isEqualTo(destinationBatch);
			var secondArrival = admin.mutate("POST", "/api/transfers/" + second.get("transfer").get("id").asText() + "/receive", receipt(arrivals(second)));
			assertThat(secondArrival.statusCode()).withFailMessage(secondArrival.body()).isEqualTo(200);
			var thirdBatch = jdbc.queryForMap("SELECT * FROM stock_batches WHERE branch_id = ?", UUID.fromString(unrelated));
			assertThat(thirdBatch.get("source_batch_id").toString()).isEqualTo(destinationBatch);
			assertThat(thirdBatch.get("product_name_snapshot")).isEqualTo("Red onions");
			assertThat(thirdBatch.get("grade_snapshot")).isEqualTo("Grade A");
			assertThat((BigDecimal) thirdBatch.get("remaining_cost")).isEqualByComparingTo("4");
			destinationSeller.login("transfer-destination", List.of("SALE_CREATE", "PROFIT_VIEW"), OidcTestProvider.Fault.NONE);
			var sale = destinationSeller.mutate("POST", "/api/sales", Map.of("requestId", UUID.randomUUID(), "paymentMethod", "CASH",
				"lines", List.of(Map.of("productId", product, "unitId", unit("KG"), "quantity", "3", "unitPrice", "5"))));
			assertThat(sale.statusCode()).withFailMessage(sale.body()).isEqualTo(201);
			assertThat(new BigDecimal(destinationSeller.body(sale).get("cost").asText())).isEqualByComparingTo("6");
			assertBatch(destination, "CHAIN", "0", "0");
			var voided = admin.mutate("POST", "/api/sales/" + destinationSeller.body(sale).get("id").asText() + "/void",
				Map.of("requestId", UUID.randomUUID(), "reason", "All received goods returned and refunded", "returnedAndRefunded", true));
			assertThat(voided.statusCode()).withFailMessage(voided.body()).isEqualTo(200);
			assertBatch(destination, "CHAIN", "3", "6");
			assertThat(jdbc.queryForObject("SELECT SUM(revenue) FROM sales_events", BigDecimal.class)).isEqualByComparingTo("0");
			assertLedgers();
		}
	}

	private void fixture(Browser admin) throws Exception {
		admin.adminLogin();
		source = branch(admin, "SOURCE");
		destination = branch(admin, "DESTINATION");
		unrelated = branch(admin, "UNRELATED");
		var type = admin.body(admin.mutate("POST", "/api/catalogue/types", Map.of("code", "ONION", "name", "Onions", "active", true))).get("id").asText();
		grade = admin.body(admin.mutate("POST", "/api/catalogue/classifications", Map.of("code", "GRADE_A", "name", "Grade A",
			"active", true, "produceTypeId", type, "kind", "GRADE"))).get("id").asText();
		product = admin.body(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "ONION_KG", "name", "Red onions",
			"active", true, "produceTypeId", type, "baseUnitId", unit("KG")))).get("id").asText();
		for (var entry : Map.of("transfer-source", source, "transfer-peer", source, "transfer-destination", destination,
				"transfer-unrelated", unrelated).entrySet()) {
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", entry.getKey(), "fullName", entry.getKey(),
				"subject", entry.getKey(), "allBranches", false, "branchIds", List.of(entry.getValue()))).statusCode()).isEqualTo(201);
		}
	}
	private String branch(Browser admin, String code) throws Exception {
		return admin.body(admin.mutate("POST", "/api/branches", Map.of("code", code, "name", code, "active", true))).get("id").asText();
	}
	private String unit(String code) {
		return jdbc.queryForObject("SELECT id FROM units_of_measure WHERE code = ?", UUID.class, code).toString();
	}
	private void receive(Browser admin, String quantity, String cost, String lot, LocalDate expiry) throws Exception {
		var line = new HashMap<String, Object>(Map.of("productId", product, "unitId", unit("KG"), "quantity", quantity,
			"purchaseUnitCost", cost, "lotCode", lot, "gradeId", grade));
		if (expiry != null) line.put("expiryDate", expiry.toString());
		var response = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", UUID.randomUUID(), "branchId", source,
			"receivedDate", today.minusDays(10).toString(), "source", "Farm", "currency", "KES", "lines", List.of(line)));
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
	}
	private Map<String, Object> dispatch(String quantity) {
		return new HashMap<>(Map.of("requestId", UUID.randomUUID(), "sourceId", source, "destinationId", destination, "lines", List.of(line(quantity))));
	}
	private Map<String, Object> line(String quantity) { return Map.of("productId", product, "quantity", quantity); }
	private Map<String, Object> arrival(JsonNode allocation, String received, String shortage, String damaged, String reason) {
		return Map.of("allocationId", allocation.get("id").asText(), "receivedQuantity", received,
			"shortageQuantity", shortage, "damagedQuantity", damaged, "reason", reason);
	}
	private List<Map<String, Object>> arrivals(JsonNode detail) {
		var result = new ArrayList<Map<String, Object>>();
		for (var allocation : detail.get("allocations")) result.add(arrival(allocation, allocation.get("quantity").asText(), "0", "0", ""));
		return result;
	}
	private Map<String, Object> receipt(List<Map<String, Object>> arrivals) { return Map.of("requestId", UUID.randomUUID(), "allocations", arrivals); }
	private void assertBatch(String branch, String lot, String quantity, String cost) {
		var row = jdbc.queryForMap("SELECT quantity, remaining_cost FROM stock_batches WHERE branch_id = ? AND lot_code = ?", UUID.fromString(branch), lot);
		assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo(quantity);
		assertThat((BigDecimal) row.get("remaining_cost")).isEqualByComparingTo(cost);
	}
	private void assertLedgers() {
		assertThat(jdbc.queryForObject("""
			SELECT COUNT(*) FROM stock_batches b WHERE b.quantity < 0 OR b.quantity <>
			(SELECT COALESCE(SUM(m.quantity), 0) FROM stock_movements m WHERE m.batch_id = b.id)
			""", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("""
			SELECT COUNT(*) FROM stock_batches b WHERE b.received_cost IS NOT NULL AND b.remaining_cost <>
			(SELECT COALESCE(SUM(m.cost_change), 0) FROM stock_movements m WHERE m.batch_id = b.id)
			""", Integer.class)).isZero();
	}
}
