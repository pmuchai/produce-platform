package com.primeroot.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false", "platform.inventory.currency=KES",
	"spring.datasource.url=jdbc:h2:mem:expense-integration-tests;MODE=PostgreSQL"})
class ExpenseIntegrationTests extends OidcTestSupport {
	@Autowired private StaffService staff;
	@Autowired private JdbcTemplate jdbc;
	private String branch;
	private String other;
	private String product;
	private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

	@BeforeEach
	void reset() {
		for (var table : List.of("audit_events", "sale_date_corrections", "expense_request_ids", "expenses", "transfer_allocations", "stock_transfers", "sale_allocations",
				"sale_lines", "sales", "stock_movements", "stock_batches", "stock_operations", "stock_thresholds",
				"product_packaging", "products", "product_classifications", "produce_types", "user_branches",
				"staff_users", "branches")) jdbc.update("DELETE FROM " + table);
		staff.bootstrap("admin", "Finance Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void recordsExactPaidExpensesWithSnapshotsReferencesIdempotencyAndNoStockChanges() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			var request = expense("123.45");
			request.put("paymentMethod", "MPESA");
			request.put("paymentReference", " outgoing-001 ");
			var result = admin.mutate("POST", "/api/expenses", request);
			assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);
			var row = admin.body(result);
			assertThat(row.get("amount").asText()).isEqualTo("123.45");
			assertThat(row.get("currency").asText()).isEqualTo("KES");
			assertThat(row.get("paymentReference").asText()).isEqualTo("OUTGOING-001");
			assertThat(row.get("branch").asText()).isEqualTo("Main branch");
			assertThat(admin.mutate("POST", "/api/expenses", request).statusCode()).isEqualTo(201);
			request.put("amount", "124.45");
			assertThat(admin.mutate("POST", "/api/expenses", request).statusCode()).isEqualTo(409);
			request.put("requestId", UUID.randomUUID());
			assertThat(admin.mutate("POST", "/api/expenses", request).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'EXPENSE_POSTED'", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
			jdbc.update("UPDATE branches SET name = 'Renamed branch' WHERE id = ?", UUID.fromString(branch));
			var history = admin.body(admin.get("/api/expenses"));
			assertThat(history.get("rows").get(0).get("branch").asText()).isEqualTo("Main branch");
			assertThat(admin.get("/ui/expenses").statusCode()).isEqualTo(200);
			assertThat(admin.get("/ui/expenses").body()).contains("123.45", "OUTGOING-001", "Record paid expense");
		}
	}

	@Test
	void validatesPaidAmountsDatesReferencesCategoriesAndIndependentBranchPermissions() throws Exception {
		try (var admin = browser(); var clerk = browser(); var viewer = browser(); var stranger = browser(); var anonymous = browser()) {
			fixture(admin);
			clerk.login("finance-main", List.of("EXPENSE_CREATE"), OidcTestProvider.Fault.NONE);
			viewer.login("finance-main", List.of("EXPENSE_VIEW"), OidcTestProvider.Fault.NONE);
			stranger.login("finance-other", List.of("EXPENSE_VIEW", "EXPENSE_CREATE", "EXPENSE_VOID", "PROFIT_VIEW"), OidcTestProvider.Fault.NONE);
			assertThat(viewer.mutate("POST", "/api/expenses", expense("1")).statusCode()).isEqualTo(403);
			assertThat(clerk.get("/api/expenses").statusCode()).isEqualTo(403);
			assertThat(clerk.get("/api/finance/reports/net-profit").statusCode()).isEqualTo(403);
			assertThat(stranger.mutate("POST", "/api/expenses", expense("1")).statusCode()).isEqualTo(403);
			assertThat(stranger.get("/api/expenses?branchId=" + branch).statusCode()).isEqualTo(403);
			assertThat(stranger.get("/api/finance/reports/net-profit?branchId=" + branch).statusCode()).isEqualTo(403);
			assertThat(anonymous.mutate("POST", "/api/expenses", expense("1")).statusCode()).isEqualTo(401);
			assertThat(clerk.send("POST", java.net.URI.create("http://localhost:" + port + "/api/expenses"), expense("1"), null).statusCode()).isEqualTo(403);
			for (var amount : List.of("0", "-1", "1.001", "1.1234567", "10000000000000")) {
				assertThat(clerk.mutate("POST", "/api/expenses", expense(amount)).statusCode()).as(amount).isEqualTo(400);
			}
			for (var change : List.<Map<String, Object>>of(Map.of("paidDate", today.plusDays(1).toString()),
					Map.of("paidDate", "1969-12-31"), Map.of("description", "  "), Map.of("category", "INVENTORY"),
					Map.of("paymentMethod", "BANK"), Map.of("paymentMethod", "MPESA", "paymentReference", "  "))) {
				var invalid = expense("1");
				invalid.putAll(change);
				assertThat(clerk.mutate("POST", "/api/expenses", invalid).statusCode()).as(change.toString()).isEqualTo(400);
			}
			var saved = clerk.body(clerk.mutate("POST", "/api/expenses", expense("1")));
			assertThat(viewer.body(viewer.get("/api/expenses")).get("rows").size()).isEqualTo(1);
			assertThat(viewer.get("/ui/expenses").body()).doesNotContain("id=\"expense-form\"", "class=\"expense-void\"");
			assertThat(stranger.body(stranger.get("/api/expenses")).get("rows").isEmpty()).isTrue();
			assertThat(stranger.mutate("POST", "/api/expenses/" + saved.get("id").asText() + "/void",
				Map.of("requestId", UUID.randomUUID(), "reason", "Out of scope")).statusCode()).isEqualTo(403);
			assertThat(admin.get("/api/expenses?page=-1").statusCode()).isEqualTo(400);
			assertThat(admin.get("/api/expenses?start=" + today).statusCode()).isEqualTo(400);
			jdbc.update("UPDATE branches SET active = FALSE WHERE id = ?", UUID.fromString(branch));
			assertThat(admin.mutate("POST", "/api/expenses", expense("1")).statusCode()).isEqualTo(409);
		}
	}

	@Test
	void voidsPreserveOriginalHistoryAndReverseOnCorrectionDateExactlyOnce() throws Exception {
		try (var admin = browser(); var viewer = browser()) {
			fixture(admin);
			var yesterday = today.minusDays(1);
			var request = expense("20");
			request.put("paidDate", yesterday.toString());
			var saved = admin.body(admin.mutate("POST", "/api/expenses", request));
			var id = saved.get("id").asText();
			viewer.login("finance-main", List.of("EXPENSE_VIEW"), OidcTestProvider.Fault.NONE);
			var reversal = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID(), "reason", "Duplicate paper record"));
			assertThat(viewer.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode()).isEqualTo(403);
			var invalid = new HashMap<>(reversal);
			invalid.put("reason", "");
			assertThat(admin.mutate("POST", "/api/expenses/" + id + "/void", invalid).statusCode()).isEqualTo(400);
			assertThat(admin.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/expenses", request).statusCode()).isEqualTo(201);
			reversal.put("reason", "Different correction");
			assertThat(admin.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode()).isEqualTo(409);
			reversal.put("requestId", UUID.randomUUID());
			assertThat(admin.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode()).isEqualTo(409);
			var prior = total(admin.body(admin.get(reportUrl(yesterday, yesterday))), "KES");
			assertThat(prior.get("netProfit").asText()).isEqualTo("-20.00");
			var current = total(admin.body(admin.get(reportUrl(today, today))), "KES");
			assertThat(current.get("expenses").asText()).isEqualTo("-20.00");
			assertThat(current.get("netProfit").asText()).isEqualTo("20.00");
			var combined = total(admin.body(admin.get(reportUrl(yesterday, today))), "KES");
			assertThat(combined.get("netProfit").asText()).isEqualTo("0.00");
			assertThat(admin.body(admin.get("/api/expenses?start=" + today + "&end=" + today)).get("rows").size()).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'EXPENSE_VOIDED'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void netProfitCombinesSalesExpensesSpoilageAndFinalTransferLossWithoutCountingPurchasesOrTransit() throws Exception {
		try (var admin = browser(); var seller = browser(); var scoped = browser()) {
			fixture(admin);
			receive(admin, "100", "10", "KNOWN");
			seller.login("finance-main", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var sale = seller.mutate("POST", "/api/sales", Map.of("requestId", UUID.randomUUID(), "paymentMethod", "CASH",
				"lines", List.of(Map.of("productId", product, "unitId", unit(), "quantity", "5", "unitPrice", "20"))));
			assertThat(sale.statusCode()).withFailMessage(sale.body()).isEqualTo(201);
			admin.mutate("POST", "/api/expenses", expense("12.34"));
			var loss = admin.mutate("POST", "/api/inventory/spoilage", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"batchId", batch("KNOWN"), "quantity", "2", "reason", "Rotten"));
			assertThat(loss.statusCode()).withFailMessage(loss.body()).isEqualTo(200);
			var dispatch = admin.mutate("POST", "/api/transfers", Map.of("requestId", UUID.randomUUID(), "sourceId", branch,
				"destinationId", other, "lines", List.of(Map.of("productId", product, "quantity", "4"))));
			assertThat(dispatch.statusCode()).withFailMessage(dispatch.body()).isEqualTo(201);
			var transit = admin.body(dispatch);
			assertThat(total(admin.body(admin.get(reportUrl(today, today))), "KES").get("netProfit").asText()).isEqualTo("17.66");
			var arrival = admin.mutate("POST", "/api/transfers/" + transit.get("transfer").get("id").asText() + "/receive",
				Map.of("requestId", UUID.randomUUID(), "allocations", List.of(Map.of(
					"allocationId", transit.get("allocations").get(0).get("id").asText(), "receivedQuantity", "3",
					"shortageQuantity", "1", "damagedQuantity", "0", "reason", "Missing at arrival"))));
			assertThat(arrival.statusCode()).withFailMessage(arrival.body()).isEqualTo(200);
			var report = admin.body(admin.get(reportUrl(today, today)));
			var amounts = total(report, "KES");
			assertThat(amounts.get("revenue").asText()).isEqualTo("100.00");
			assertThat(amounts.get("costOfGoodsSold").asText()).isEqualTo("50.00");
			assertThat(amounts.get("grossProfit").asText()).isEqualTo("50.00");
			assertThat(amounts.get("stockLosses").asText()).isEqualTo("30.00");
			assertThat(amounts.get("netProfit").asText()).isEqualTo("7.66");
			var otherExpense = expense("9");
			otherExpense.put("branchId", other);
			admin.mutate("POST", "/api/expenses", otherExpense);
			scoped.login("finance-main", List.of("PROFIT_VIEW"), OidcTestProvider.Fault.NONE);
			assertThat(total(scoped.body(scoped.get(reportUrl(today, today))), "KES").get("netProfit").asText()).isEqualTo("7.66");
			assertThat(total(admin.body(admin.get(reportUrl(today, today))), "KES").get("netProfit").asText()).isEqualTo("-1.34");
			assertThat(scoped.get("/ui/reports/net-profit").body()).contains("12.34", "Net profit after expenses and losses")
				.doesNotContain("id=\"nav-expenses\"");
			assertThat(scoped.get("/home").body()).contains("id=\"net-profit-card\"", "id=\"expenses-card\"",
				"data-amount=\"12.34\"", "data-amount=\"7.66\"", "id=\"stock-losses-card\"",
				"data-amount=\"30.00\"", "data-spoilage-count=\"1\"");
			assertThat(scoped.get("/home?currency=USD").body()).contains("data-spoilage-count=\"0\"");
			assertThat(scoped.get("/home?month=2024-01").body()).contains("data-spoilage-count=\"0\"");
			var spoilageMovement = admin.body(loss).get("movements").get(0).get("id").asText();
			assertThat(admin.mutate("POST", "/api/inventory/reversals", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"movementId", spoilageMovement, "reason", "Spoilage entered in error")).statusCode()).isEqualTo(200);
			assertThat(total(scoped.body(scoped.get(reportUrl(today, today))), "KES").get("stockLosses").asText()).isEqualTo("10.00");
			assertThat(scoped.get("/home").body()).contains("data-amount=\"10.00\"", "data-spoilage-count=\"1\"");
			var saleId = seller.body(sale).get("id").asText();
			assertThat(admin.mutate("POST", "/api/sales/" + saleId + "/void", Map.of("requestId", UUID.randomUUID(),
				"reason", "Full return", "returnedAndRefunded", true)).statusCode()).isEqualTo(200);
			var voided = total(scoped.body(scoped.get(reportUrl(today, today))), "KES");
			assertThat(voided.get("grossProfit").asText()).isEqualTo("0.00");
			assertThat(voided.get("netProfit").asText()).isEqualTo("-22.34");
		}
	}

	@Test
	void keepsCurrenciesSeparateTotalsUnpaginatedAndUnknownLossCostsUnavailable() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			admin.mutate("POST", "/api/expenses", expense("10"));
			var dollar = admin.body(admin.mutate("POST", "/api/expenses", expense("5"))).get("id").asText();
			jdbc.update("UPDATE expenses SET currency = 'USD' WHERE id = ?", UUID.fromString(dollar));
			var report = admin.body(admin.get(reportUrl(today, today)));
			assertThat(report.get("totals").size()).isEqualTo(2);
			assertThat(total(report, "KES").get("netProfit").asText()).isEqualTo("-10.00");
			assertThat(total(report, "USD").get("netProfit").asText()).isEqualTo("-5.00");
			assertThat(admin.body(admin.get(reportUrl(today, today) + "&page=1")).get("rows").isEmpty()).isTrue();
			assertThat(admin.body(admin.get(reportUrl(today, today) + "&page=1")).get("totals").size()).isEqualTo(2);
			receive(admin, "2", "1", "UNKNOWN");
			var posted = admin.mutate("POST", "/api/inventory/spoilage", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"batchId", batch("UNKNOWN"), "quantity", "1", "reason", "Legacy loss"));
			assertThat(posted.statusCode()).isEqualTo(200);
			jdbc.update("UPDATE stock_batches SET currency = NULL, purchase_unit_cost = NULL, received_cost = NULL, remaining_cost = NULL WHERE lot_code = 'UNKNOWN'");
			jdbc.update("UPDATE stock_movements SET cost_change = NULL WHERE batch_id = ?", UUID.fromString(batch("UNKNOWN")));
			var unknown = admin.body(admin.get(reportUrl(today, today)));
			assertThat(total(unknown, "UNKNOWN").get("unknownCosts").asInt()).isEqualTo(1);
			assertThat(total(unknown, "KES").get("netProfit").isNull()).isTrue();
			assertThat(total(unknown, "USD").get("netProfit").isNull()).isTrue();
			assertThat(admin.get("/ui/reports/net-profit").body()).contains("Net profit unavailable: unknown loss costs");
			var movement = admin.body(posted).get("movements").get(0).get("id").asText();
			admin.mutate("POST", "/api/inventory/reversals", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"movementId", movement, "reason", "Undo legacy loss"));
			assertThat(total(admin.body(admin.get(reportUrl(today, today))), "KES").get("netProfit").asText()).isEqualTo("-10.00");
			jdbc.update("UPDATE expenses SET paid_date = DATE '2024-01-31'");
			jdbc.update("""
				UPDATE stock_operations SET posted_at = TIMESTAMP WITH TIME ZONE '2024-02-04 00:00:00+00:00'
				WHERE id = (SELECT operation_id FROM stock_movements WHERE id = ?)
				""", UUID.fromString(movement));
			var dashboard = admin.get("/home?month=2024-02").body();
			assertThat(dashboard).contains("Unavailable: unknown loss costs")
				.doesNotContain("KES / change", "USD / change");
		}
	}

	@Test
	void unrelatedUnknownLossAndOutOfRangeReversalCannotCancelEachOther() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			admin.mutate("POST", "/api/expenses", expense("1"));
			receive(admin, "3", "1", "UNKNOWN");
			jdbc.update("UPDATE stock_batches SET currency = NULL, purchase_unit_cost = NULL, received_cost = NULL, remaining_cost = NULL WHERE lot_code = 'UNKNOWN'");
			jdbc.update("UPDATE stock_movements SET cost_change = NULL WHERE batch_id = ?", UUID.fromString(batch("UNKNOWN")));
			var original = admin.mutate("POST", "/api/inventory/spoilage", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"batchId", batch("UNKNOWN"), "quantity", "1", "reason", "Older unknown loss"));
			assertThat(original.statusCode()).withFailMessage(original.body()).isEqualTo(200);
			var originalOperation = admin.body(original).get("id").asText();
			jdbc.update("UPDATE stock_operations SET posted_at = ? WHERE id = ?", java.sql.Timestamp.from(
				today.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)), UUID.fromString(originalOperation));
			var originalMovement = admin.body(original).get("movements").get(0).get("id").asText();
			var reversal = admin.mutate("POST", "/api/inventory/reversals", Map.of("requestId", UUID.randomUUID(),
				"branchId", branch, "movementId", originalMovement, "reason", "Reverse older unknown loss"));
			assertThat(reversal.statusCode()).withFailMessage(reversal.body()).isEqualTo(200);
			var reversalOnly = admin.body(admin.get(reportUrl(today, today)));
			assertThat(total(reversalOnly, "UNKNOWN").get("unknownCosts").asInt()).isEqualTo(1);
			assertThat(total(reversalOnly, "KES").get("netProfit").isNull()).isTrue();
			var currentLoss = admin.mutate("POST", "/api/inventory/spoilage", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"batchId", batch("UNKNOWN"), "quantity", "1", "reason", "Current unknown loss"));
			assertThat(currentLoss.statusCode()).withFailMessage(currentLoss.body()).isEqualTo(200);

			var report = admin.body(admin.get(reportUrl(today, today)));
			assertThat(total(report, "UNKNOWN").get("unknownCosts").asInt()).isEqualTo(2);
			assertThat(total(report, "KES").get("netProfit").isNull()).isTrue();
		}
	}

	@Test
	void concurrentPostingAndVoidingRetriesNeverDoubleCountExpenses() throws Exception {
		try (var admin = browser(); var peer = browser()) {
			fixture(admin);
			peer.adminLogin();
			var request = expense("15");
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/expenses", request).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return peer.mutate("POST", "/api/expenses", request).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsOnly(201);
			}
			var id = request.get("requestId").toString();
			var reversal = Map.of("requestId", UUID.randomUUID(), "reason", "Wrong entry");
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return peer.mutate("POST", "/api/expenses/" + id + "/void", reversal).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsOnly(200);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'EXPENSE_POSTED'", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'EXPENSE_VOIDED'", Integer.class)).isEqualTo(1);
			assertThat(total(admin.body(admin.get(reportUrl(today, today))), "KES").get("netProfit").asText()).isEqualTo("0.00");
		}
	}

	@Test
	void concurrentCreateAndVoidCannotClaimTheSameRequestIdAcrossBranches() throws Exception {
		try (var admin = browser(); var peer = browser()) {
			fixture(admin);
			peer.adminLogin();
			var existing = admin.body(admin.mutate("POST", "/api/expenses", expense("15")));
			var request = expense("7");
			request.put("branchId", other);
			var sharedRequestId = UUID.randomUUID();
			request.put("requestId", sharedRequestId);
			var reversal = Map.of("requestId", sharedRequestId, "reason", "Wrong entry");
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var create = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/expenses", request).statusCode(); });
				var voidExpense = pool.submit(() -> { ready.await(); return peer.mutate("POST",
					"/api/expenses/" + existing.get("id").asText() + "/void", reversal).statusCode(); });
				ready.countDown();
				var statuses = List.of(create.get(20, TimeUnit.SECONDS), voidExpense.get(20, TimeUnit.SECONDS));
				assertThat(statuses).contains(409);
				assertThat(statuses.stream().filter(status -> status == 200 || status == 201)).hasSize(1);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isBetween(1, 2);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_request_ids WHERE request_id = ?",
				Integer.class, sharedRequestId)).isEqualTo(1);
		}
	}

	private void fixture(Browser admin) throws Exception {
		admin.adminLogin();
		branch = admin.body(admin.mutate("POST", "/api/branches", Map.of("code", "MAIN", "name", "Main branch", "active", true))).get("id").asText();
		other = admin.body(admin.mutate("POST", "/api/branches", Map.of("code", "OTHER", "name", "Other branch", "active", true))).get("id").asText();
		var type = admin.body(admin.mutate("POST", "/api/catalogue/types", Map.of("code", "ONION", "name", "Onions", "active", true))).get("id").asText();
		product = admin.body(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "ONION_KG", "name", "Red onions",
			"active", true, "produceTypeId", type, "baseUnitId", unit()))).get("id").asText();
		for (var item : Map.of("finance-main", branch, "finance-other", other).entrySet()) {
			assertThat(admin.mutate("POST", "/api/users", Map.of("username", item.getKey(), "fullName", item.getKey(),
				"subject", item.getKey(), "allBranches", false, "branchIds", List.of(item.getValue()))).statusCode()).isEqualTo(201);
		}
	}

	private Map<String, Object> expense(String amount) {
		return new HashMap<>(Map.of("requestId", UUID.randomUUID(), "branchId", branch, "paidDate", today.toString(),
			"category", "TRANSPORT", "description", "Delivery fuel", "amount", amount, "paymentMethod", "CASH"));
	}

	private void receive(Browser admin, String quantity, String cost, String lot) throws Exception {
		var response = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
			"receivedDate", today.toString(), "source", "Farm", "currency", "KES",
			"lines", List.of(Map.of("productId", product, "unitId", unit(), "quantity", quantity, "purchaseUnitCost", cost, "lotCode", lot))));
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
	}

	private String unit() { return jdbc.queryForObject("SELECT id FROM units_of_measure WHERE code = 'KG'", UUID.class).toString(); }
	private String batch(String lot) { return jdbc.queryForObject("SELECT id FROM stock_batches WHERE lot_code = ?", UUID.class, lot).toString(); }
	private String reportUrl(LocalDate start, LocalDate end) { return "/api/finance/reports/net-profit?start=" + start + "&end=" + end; }
	private JsonNode total(JsonNode report, String currency) {
		assertThat(report.has("totals")).withFailMessage(report.toString()).isTrue();
		for (var total : report.get("totals")) if (total.get("currency").asText().equals(currency)) return total;
		throw new AssertionError("Missing currency " + currency + " in " + report);
	}
}
