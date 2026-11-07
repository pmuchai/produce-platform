package com.primeroot.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
	"spring.datasource.url=jdbc:h2:mem:sales-integration-tests;MODE=PostgreSQL"})
class SalesIntegrationTests extends OidcTestSupport {
	@Autowired private StaffService staff;
	@Autowired private JdbcTemplate jdbc;
	private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
	private String branch;
	private String otherBranch;
	private String product;

	@BeforeEach
	void reset() {
		for (var table : List.of("audit_events", "transfer_allocations", "stock_transfers", "sale_allocations", "sale_lines", "sales", "stock_movements",
				"stock_batches", "stock_operations", "stock_thresholds", "product_packaging", "products",
				"product_classifications", "produce_types", "user_branches", "staff_users", "branches")) {
			jdbc.update("DELETE FROM " + table);
		}
		staff.bootstrap("admin", "Sales Administrator", OidcTestProvider.ADMIN_SUBJECT);
	}

	@Test
	void saleEntryShowsOnlyAssignedBranchStockAndOnlyEligibleSalespeopleLandThere() throws Exception {
		try (var admin = browser(); var seller = browser(); var viewer = browser()) {
			fixture(admin);
			receive(admin, "4", "1", "ENTRY", null);
			receive(admin, "2", "1", "ENTRY_EXPIRED", today.minusDays(1));
			assertThat(admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", UUID.randomUUID(),
				"branchId", otherBranch, "receivedDate", today.toString(), "source", "Other farm", "currency", "KES",
				"lines", List.of(receiptLine("91", "1", "OTHER_ENTRY", null)))).statusCode()).isEqualTo(201);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var home = seller.get("/home");
			assertThat(home.statusCode()).isEqualTo(302);
			assertThat(home.headers().firstValue("Location").orElseThrow()).endsWith("/ui/sales");
			for (var path : List.of("/", "/login")) {
				var redirect = seller.get(path);
				assertThat(redirect.statusCode()).isEqualTo(302);
				assertThat(seller.get(redirect.headers().firstValue("Location").orElseThrow())
					.headers().firstValue("Location").orElseThrow()).endsWith("/ui/sales");
			}
			var entry = seller.body(seller.get("/api/sales/entry"));
			assertThat(entry.get("branchId").asText()).isEqualTo(branch);
			var option = entry.get("products").get(0);
			assertThat(option.get("id").asText()).isEqualTo(product);
			assertThat(option.get("baseUnitId").asText()).isEqualTo(unit("KG"));
			assertThat(option.get("quantity").asText()).isEqualTo("6.000000");
			assertThat(option.get("baseUnit").asText()).isEqualTo("KG");
			var page = seller.get("/ui/sales");
			assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
			assertThat(page.body()).contains("Current sale", "On hand: 6 KG", "Full payment received")
				.doesNotContain("Phase 6", "Stock is deducted now", "id=\"nav-dashboard\"");
			var quote = seller.body(seller.mutate("POST", "/api/sales/quote",
				Map.of("lines", List.of(saleLine("1.000001", "0.015"), saleLine("2.33", "12.345")))));
			assertThat(quote.get("lines").get(0).get("total").asText()).isEqualTo("0.02");
			assertThat(quote.get("lines").get(0).get("total").isString()).isTrue();
			assertThat(quote.get("lines").get(1).get("total").asText()).isEqualTo("28.76");
			assertThat(quote.get("total").asText()).isEqualTo("28.78");
			assertBatch("ENTRY", "4", "4");
			assertThat(admin.get("/home").statusCode()).isEqualTo(200);
			viewer.login("sale-one", List.of("SALES_VIEW"), OidcTestProvider.Fault.NONE);
			assertThat(viewer.get("/home").statusCode()).isEqualTo(200);

			jdbc.update("UPDATE branches SET active = false WHERE id = ?", UUID.fromString(branch));
			assertThat(seller.get("/home").statusCode()).isEqualTo(200);
			assertThat(seller.get("/api/sales/entry").statusCode()).isEqualTo(409);
			jdbc.update("INSERT INTO user_branches(user_id, branch_id) SELECT id, ? FROM staff_users WHERE subject = 'sale-one'",
				UUID.fromString(otherBranch));
			assertThat(seller.get("/home").statusCode()).isEqualTo(200);
			assertThat(seller.get("/api/sales/entry").statusCode()).isEqualTo(409);
			jdbc.update("UPDATE branches SET active = true WHERE id = ?", UUID.fromString(branch));
			assertThat(seller.get("/home").statusCode()).isEqualTo(200);
			assertThat(seller.get("/api/sales/entry").statusCode()).isEqualTo(409);
		}
	}

	@Test
	void fefoSplitsBatchesSnapshotsCostsAndRetriesWithoutAllowingBranchSelection() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "100", "100", "LATE", today.plusDays(20));
			receive(admin, "50", "60", "EARLY", today.plusDays(2));
			receive(admin, "30", "200", "UNDATED", null);
			receive(admin, "100", "20", "EXPIRED", today.minusDays(1));
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("75", "120");
			request.put("branchId", otherBranch);
			var posted = seller.mutate("POST", "/api/sales", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			var receipt = seller.body(posted);
			assertThat(receipt.get("branchId").asText()).isEqualTo(branch);
			assertThat(receipt.get("customer").asText()).isEqualTo("Walk-ins");
			assertThat(receipt.get("saleDate").asText()).isEqualTo(today.toString());
			var known = new HashMap<>(request);
			known.remove("branchId");
			var typed = json.readValue(json.writeValueAsString(known), com.primeroot.platform.sales.SalesDtos.CreateRequest.class);
			record LegacyRequest(UUID requestId, String customer, com.primeroot.platform.sales.Sale.PaymentMethod paymentMethod,
				String paymentReference, String notes, List<com.primeroot.platform.sales.SalesDtos.LineRequest> lines) {}
			var legacyHash = com.primeroot.platform.inventory.PostingFingerprint.of(json,
				com.primeroot.platform.inventory.StockOperation.Kind.SALE, UUID.fromString(receipt.get("sellerId").asText()),
				new LegacyRequest(typed.requestId(), typed.customer(), typed.paymentMethod(), typed.paymentReference(), typed.notes(), typed.lines()));
			assertThat(jdbc.queryForObject("SELECT request_hash FROM stock_operations WHERE id = ?", String.class,
				UUID.fromString(receipt.get("id").asText()))).isEqualTo(legacyHash);
			assertThat(receipt.get("total").asText()).isEqualTo("9000.00");
			assertThat(receipt.get("cost").isNull()).isTrue();
			assertThat(receipt.get("lines").get(0).get("allocations").size()).isEqualTo(2);
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(201);
			request.put("lines", List.of(saleLine("76", "120")));
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(409);
			var full = admin.body(admin.get("/api/sales/" + receipt.get("id").asText()));
			assertThat(full.get("cost").asText()).isEqualTo("5500.00");
			assertThat(full.get("grossProfit").asText()).isEqualTo("3500.00");
			assertBatch("EARLY", "0", "0");
			assertBatch("LATE", "75", "7500");
			assertBatch("UNDATED", "30", "6000");
			assertBatch("EXPIRED", "100", "2000");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(1);
			assertLedgers();
		}
	}

	@Test
	void insufficientExpiredUnknownCostAndInvalidMultiLineSalesRollbackCompletely() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "AVAILABLE", today.plusDays(1));
			receive(admin, "100", "20", "EXPIRED", today.minusDays(1));
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			assertThat(seller.mutate("POST", "/api/sales", sale("11", "100")).statusCode()).isEqualTo(409);
			var multi = sale("1", "100");
			multi.put("lines", List.of(saleLine("5", "100"), saleLine("11", "100")));
			assertThat(seller.mutate("POST", "/api/sales/quote", Map.of("lines", multi.get("lines"))).statusCode()).isEqualTo(409);
			assertThat(seller.mutate("POST", "/api/sales", multi).statusCode()).isEqualTo(409);
			assertBatch("AVAILABLE", "10", "800");
			jdbc.update("UPDATE stock_batches SET currency = NULL, purchase_unit_cost = NULL, received_cost = NULL, remaining_cost = NULL WHERE lot_code = 'AVAILABLE'");
			assertThat(seller.mutate("POST", "/api/sales", sale("1", "100")).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations WHERE kind = 'SALE'", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SALE_POSTED'", Integer.class)).isZero();
			var values = admin.body(admin.get("/api/sales/reports/stock-valuation"));
			assertThat(values.get("rows").size()).isEqualTo(2);
			assertThat(values.get("rows").toString()).contains("UNKNOWN", "\"unknownCosts\":1");
			assertLedgers();
		}
	}

	@Test
	void sellingUnitsMeasuredPackagingAndFinancialValidationPreserveExactConversionSnapshots() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "100", "80", "AVAILABLE", null);
			var packaging = admin.mutate("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", unit("CRATE"), "quantityInBaseUnit", "20"));
			assertThat(packaging.statusCode()).isEqualTo(200);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var fixed = sale("1", "1");
			fixed.put("lines", List.of(Map.of("productId", product, "unitId", unit("CRATE"), "quantity", "2", "unitPrice", "2000")));
			var posted = seller.mutate("POST", "/api/sales", fixed);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			var id = seller.body(posted).get("id").asText();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/catalogue/packaging", Map.of("productId", product,
				"unitId", unit("CRATE"), "quantityInBaseUnit", "25"), packaging.headers().firstValue("ETag").orElseThrow()).statusCode()).isEqualTo(200);
			var original = admin.body(admin.get("/api/sales/" + id)).get("lines").get(0);
			assertThat(original.get("baseQuantity").asText()).isEqualTo("40.000000");
			assertThat(original.get("conversionNumerator").asText()).isEqualTo("20.000000");
			assertThat(original.get("cost").asText()).isEqualTo("3200.00");
			var measured = sale("1", "1");
			measured.put("lines", List.of(Map.of("productId", product, "unitId", unit("BAG"), "quantity", "2",
				"measuredBaseQuantity", "15.5", "unitPrice", "1000")));
			assertThat(seller.mutate("POST", "/api/sales", measured).statusCode()).isEqualTo(201);
			var grams = sale("1", "1");
			grams.put("lines", List.of(Map.of("productId", product, "unitId", unit("G"), "quantity", "1000", "unitPrice", "0.125")));
			assertThat(seller.mutate("POST", "/api/sales", grams).statusCode()).isEqualTo(201);
			for (var invalid : List.of(Map.of("productId", product, "unitId", unit("PIECE"), "quantity", "1", "unitPrice", "1"),
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "1", "unitPrice", "0"),
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "1", "unitPrice", "-1"),
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "1", "unitPrice", "1.1234567"),
					Map.of("productId", product, "unitId", unit("KG"), "quantity", "0.000001", "unitPrice", "0.000001"))) {
				var request = sale("1", "1");
				request.put("lines", List.of(invalid));
				assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(400);
			}
			var productReport = admin.body(admin.get("/api/sales/reports/product-sales"));
			assertThat(new BigDecimal(productReport.get("rows").get(0).get("quantity").asText())).isEqualByComparingTo("56.5");
			assertThat(productReport.get("totals").get(0).get("revenue").asText()).isEqualTo("6125.00");
			assertBatch("AVAILABLE", "43.5", "3480");
			assertLedgers();
		}
	}

	@Test
	void manualPaymentReferencesAndCustomerDropdownAreValidatedAndBranchScoped() throws Exception {
		try (var admin = browser(); var seller = browser(); var other = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "AVAILABLE", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("2", "125.555");
			var quote = seller.mutate("POST", "/api/sales/quote", Map.of("lines", request.get("lines")));
			assertThat(quote.statusCode()).isEqualTo(200);
			assertThat(seller.body(quote).get("total").asText()).isEqualTo("251.11");
			assertBatch("AVAILABLE", "10", "800");
			request.put("paymentMethod", "MPESA");
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(400);
			request.put("paymentReference", " mpesa-test-001 ");
			request.put("customer", "Fresh Grocers");
			var response = seller.mutate("POST", "/api/sales", request);
			assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
			var receipt = seller.body(response);
			assertThat(receipt.get("total").asText()).isEqualTo("251.11");
			assertThat(receipt.get("paymentReference").asText()).isEqualTo("MPESA-TEST-001");
			request.put("requestId", UUID.randomUUID().toString());
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(409);
			assertThat(seller.body(seller.get("/api/sales/entry")).get("customers").toString()).contains("Fresh Grocers");
			assertThat(seller.get("/ui/sales").body()).contains("value=\"Walk-ins\" selected", "Add customer...", "Fresh Grocers")
				.doesNotContain("name=\"branchId\"");
			other.login("other-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			assertThat(other.body(other.get("/api/sales/entry")).get("customers").isEmpty()).isTrue();
			assertThat(other.get("/api/sales/" + receipt.get("id").asText()).statusCode()).isEqualTo(403);
			assertBatch("AVAILABLE", "8", "640");
			assertLedgers();
		}
	}

	@Test
	void salesViewsAreOwnOnlyUnlessGrantedBranchReportAccessAndCostsNeedSeparatePermission() throws Exception {
		try (var admin = browser(); var seller = browser(); var peer = browser(); var manager = browser(); var anonymous = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "AVAILABLE", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			peer.login("sale-two", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var first = seller.body(seller.mutate("POST", "/api/sales", sale("1", "100")));
			peer.mutate("POST", "/api/sales", sale("2", "100"));
			assertThat(peer.get("/api/sales/" + first.get("id").asText()).statusCode()).isEqualTo(403);
			var own = seller.body(seller.get("/api/sales/reports/staff-performance"));
			assertThat(own.get("ownSalesOnly").asBoolean()).isTrue();
			assertThat(own.get("rows").size()).isEqualTo(1);
			assertThat(own.get("totals").get(0).get("revenue").asText()).isEqualTo("100.00");
			assertThat(own.get("totals").get(0).get("cost").isNull()).isTrue();
			assertThat(seller.get("/api/sales/reports/profit").statusCode()).isEqualTo(403);
			assertThat(seller.get("/api/sales/reports/stock-valuation").statusCode()).isEqualTo(403);
			assertThat(seller.get("/api/sales/reports/sales?branchId=" + otherBranch).statusCode()).isEqualTo(403);
			manager.login("manager-one", List.of("SALES_VIEW", "PROFIT_VIEW", "SALE_VOID"), OidcTestProvider.Fault.NONE);
			var report = manager.body(manager.get("/api/sales/reports/staff-performance"));
			assertThat(report.get("rows").size()).isEqualTo(2);
			assertThat(report.get("totals").get(0).get("revenue").asText()).isEqualTo("300.00");
			assertThat(report.get("totals").get(0).get("cost").asText()).isEqualTo("240.00");
			assertThat(manager.get("/api/sales/reports/profit?branchId=" + otherBranch).statusCode()).isEqualTo(403);
			assertThat(admin.get("/api/sales/entry").statusCode()).isEqualTo(409);
			approve(admin, "multi", "multi-branch", List.of(branch, otherBranch));
			manager.login("multi-branch", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			assertThat(manager.mutate("POST", "/api/sales", sale("1", "100")).statusCode()).isEqualTo(409);
			assertThat(anonymous.get("/api/sales/" + first.get("id").asText()).statusCode()).isEqualTo(401);
			assertThat(seller.send("POST", java.net.URI.create("http://localhost:" + port + "/api/sales"), sale("1", "100"), null).statusCode()).isEqualTo(403);
			assertLedgers();
		}
	}

	@Test
	void onlyFullConfirmedVoidsRestoreOriginalStockCostsAndPaymentsExactlyOnce() throws Exception {
		try (var admin = browser(); var seller = browser(); var manager = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "AVAILABLE", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("2", "100");
			request.put("paymentMethod", "BANK");
			request.put("paymentReference", "BANK-001");
			var receipt = seller.body(seller.mutate("POST", "/api/sales", request));
			var id = receipt.get("id").asText();
			var stockMovement = jdbc.queryForObject("SELECT id FROM stock_movements WHERE operation_id = ?", UUID.class, UUID.fromString(id)).toString();
			assertThat(admin.mutate("POST", "/api/inventory/reversals", Map.of("requestId", UUID.randomUUID(), "branchId", branch,
				"movementId", stockMovement, "reason", "Must use sale void")).statusCode()).isEqualTo(409);
			var reversal = voidRequest();
			assertThat(seller.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(403);
			manager.login("manager-one", List.of("SALES_VIEW", "PROFIT_VIEW", "SALE_VOID"), OidcTestProvider.Fault.NONE);
			reversal.put("returnedAndRefunded", false);
			assertThat(manager.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(400);
			reversal.put("returnedAndRefunded", true);
			reversal.remove("refundReference");
			assertThat(manager.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(400);
			reversal.put("refundReference", "BANK-REFUND-001");
			var branchEtag = admin.get("/api/branches/" + branch).headers().firstValue("ETag").orElseThrow();
			assertThat(admin.mutateWithIfMatch("PUT", "/api/branches/" + branch,
				Map.of("code", "MAIN", "name", "Main branch", "active", false), branchEtag).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(200);
			assertThat(admin.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(200);
			reversal.put("requestId", UUID.randomUUID());
			assertThat(admin.mutate("POST", "/api/sales/" + id + "/void", reversal).statusCode()).isEqualTo(409);
			var report = admin.body(admin.get("/api/sales/reports/payments"));
			assertThat(report.get("totals").get(0).get("revenue").asText()).isEqualTo("0.00");
			assertThat(report.get("totals").get(0).get("cost").asText()).isEqualTo("0.00");
			assertThat(report.get("totals").get(0).get("voids").asLong()).isEqualTo(1);
			assertBatch("AVAILABLE", "10", "800");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SALE_VOIDED'", Integer.class)).isEqualTo(1);
			assertLedgers();
		}
	}

	@Test
	void fractionalCentsAndInterveningSalesVoidsAndSpoilageConserveTheOriginalBatchCost() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "3", "0.003333", "PENNY", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var first = seller.body(seller.mutate("POST", "/api/sales", sale("1", "1")));
			var second = seller.body(seller.mutate("POST", "/api/sales", sale("1", "1")));
			assertThat(admin.mutate("POST", "/api/sales/" + first.get("id").asText() + "/void", voidRequest()).statusCode()).isEqualTo(200);
			assertThat(seller.mutate("POST", "/api/sales", sale("2", "1")).statusCode()).isEqualTo(201);
			assertThat(admin.mutate("POST", "/api/sales/" + second.get("id").asText() + "/void", voidRequest()).statusCode()).isEqualTo(200);
			var batchId = jdbc.queryForObject("SELECT id FROM stock_batches WHERE lot_code = 'PENNY'", UUID.class);
			assertThat(admin.mutate("POST", "/api/inventory/spoilage", Map.of("requestId", UUID.randomUUID(),
				"branchId", branch, "batchId", batchId, "quantity", "1", "reason", "Returned damaged goods")).statusCode()).isEqualTo(200);
			assertBatch("PENNY", "0", "0");
			var report = admin.body(admin.get("/api/sales/reports/stock-losses"));
			assertThat(report.get("rows").get(0).get("cost").asText()).isEqualTo("0.01");
			assertLedgers();
		}
	}

	@Test
	void backdatedSalesUseSelectedDateWhileVoidsKeepActualPostingDateAndAuditTime() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "AVAILABLE", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("2", "100");
			request.put("saleDate", today.minusDays(2).toString());
			var response = seller.mutate("POST", "/api/sales", request);
			assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
			var posted = seller.body(response);
			var id = posted.get("id").asText();
			assertThat(posted.get("saleDate").asText()).isEqualTo(today.minusDays(2).toString());
			assertThat(java.time.Instant.parse(posted.get("postedAt").asText()).atOffset(ZoneOffset.UTC).toLocalDate()).isEqualTo(today);
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(201);
			request.put("saleDate", today.minusDays(1).toString());
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SALE_BACKDATED'", Integer.class)).isEqualTo(1);
			var priorNet = admin.body(admin.get("/api/finance/reports/net-profit?start=" + today.minusDays(2) + "&end=" + today.minusDays(2)));
			assertThat(priorNet.get("totals").get(0).get("netProfit").asText()).isEqualTo("40.00");
			admin.mutate("POST", "/api/sales/" + id + "/void", voidRequest());
			var historical = admin.body(admin.get("/api/sales/reports/profit?start=" + today.minusDays(2) + "&end=" + today.minusDays(2)));
			assertThat(historical.get("totals").get(0).get("revenue").asText()).isEqualTo("200.00");
			assertThat(historical.get("totals").get(0).get("grossProfit").asText()).isEqualTo("40.00");
			var ledger = admin.body(admin.get("/api/sales/reports/sales?start=" + today.minusDays(2) + "&end=" + today.minusDays(2)));
			assertThat(ledger.get("rows").get(0).get("label").asText()).startsWith(today.minusDays(2) + " 00:00:00");
			var current = admin.body(admin.get("/api/sales/reports/profit?start=" + today + "&end=" + today));
			assertThat(current.get("totals").get(0).get("revenue").asText()).isEqualTo("-200.00");
			assertThat(current.get("totals").get(0).get("grossProfit").asText()).isEqualTo("-40.00");
			for (var kind : List.of("sales", "daily-sales", "product-sales", "branch-sales", "staff-performance",
					"customer-sales", "payments", "profit", "stock-valuation", "stock-losses")) {
				var api = admin.get("/api/sales/reports/" + kind);
				assertThat(api.statusCode()).as(kind).withFailMessage(api.body()).isEqualTo(200);
				var page = admin.get("/ui/reports/" + kind);
				assertThat(page.statusCode()).as(kind).withFailMessage(page.body()).isEqualTo(200);
			}
			assertThat(admin.get("/ui/sales/" + id).statusCode()).isEqualTo(200);
			assertThat(admin.get("/api/sales/reports/sales?page=-1").statusCode()).isEqualTo(400);
			assertLedgers();
		}
	}

	@Test
	void backdatingValidatesDatesAndOnlyUsesStockReceivedAndUnexpiredOnTheSelectedDate() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "5", "10", "SOLD_YESTERDAY", today.minusDays(1));
			receive(admin, "100", "10", "ARRIVED_TODAY", null);
			jdbc.update("UPDATE stock_batches SET received_date = ? WHERE lot_code = 'ARRIVED_TODAY'", today);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			for (var date : List.of(today.plusDays(1).toString(), "1969-12-31", "not-a-date")) {
				var request = sale("1", "20");
				request.put("saleDate", date);
				assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).as(date).isEqualTo(400);
				assertThat(seller.mutate("POST", "/api/sales/quote",
					Map.of("lines", request.get("lines"), "saleDate", date)).statusCode()).as(date).isEqualTo(400);
			}
			var request = sale("6", "20");
			request.put("saleDate", today.minusDays(1).toString());
			assertThat(seller.mutate("POST", "/api/sales/quote",
				Map.of("lines", request.get("lines"), "saleDate", request.get("saleDate"))).statusCode()).isEqualTo(409);
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
			request.put("lines", List.of(saleLine("5", "20")));
			assertThat(seller.mutate("POST", "/api/sales/quote",
				Map.of("lines", request.get("lines"), "saleDate", request.get("saleDate"))).statusCode()).isEqualTo(200);
			var posted = seller.mutate("POST", "/api/sales", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			assertBatch("SOLD_YESTERDAY", "0", "0");
			assertBatch("ARRIVED_TODAY", "100", "1000");
			assertThat(seller.get("/ui/sales").body()).contains("name=\"saleDate\"", "value=\"" + today + "\"");
			assertThat(admin.get("/ui/sales/" + seller.body(posted).get("id").asText()).body())
				.contains("Sale date (UTC)", today.minusDays(1).toString(), "Recorded at:");
			assertLedgers();
		}
	}

	@Test
	void transferredStockCannotBeSoldBeforeItsArrivalDespiteOriginalSourceReceiptDate() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "10", "10", "TRANSFERRED", null);
			var response = admin.mutate("POST", "/api/transfers", Map.of("requestId", UUID.randomUUID(),
				"sourceId", branch, "destinationId", otherBranch, "lines", List.of(Map.of("productId", product, "quantity", "3"))));
			assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
			var transfer = admin.body(response);
			var allocation = transfer.get("allocations").get(0);
			assertThat(admin.mutate("POST", "/api/transfers/" + transfer.get("transfer").get("id").asText() + "/receive",
				Map.of("requestId", UUID.randomUUID(), "allocations", List.of(Map.of("allocationId", allocation.get("id").asText(),
					"receivedQuantity", "3", "shortageQuantity", "0", "damagedQuantity", "0")))).statusCode()).isEqualTo(200);
			seller.login("other-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("1", "20");
			request.put("saleDate", today.minusDays(1).toString());
			assertThat(seller.mutate("POST", "/api/sales/quote",
				Map.of("lines", request.get("lines"), "saleDate", request.get("saleDate"))).statusCode()).isEqualTo(409);
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(409);
			request.put("saleDate", today.toString());
			assertThat(seller.mutate("POST", "/api/sales", request).statusCode()).isEqualTo(201);
			assertLedgers();
		}
	}

	@Test
	void allocationCrossesBatchPagesAndReportTotalsAreNotLimitedToTheFirstHundredSales() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			for (int receipt = 0; receipt < 3; receipt++) {
				var items = new ArrayList<Map<String, Object>>();
				for (int line = 0; line < 40; line++) items.add(receiptLine("1", "1", "LOT-" + receipt + "-" + line, null));
				receiveLines(admin, items);
			}
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var bulk = seller.body(seller.mutate("POST", "/api/sales", sale("101", "2")));
			assertThat(bulk.get("lines").get(0).get("allocations").size()).isEqualTo(101);
			receive(admin, "100", "1", "MORE", null);
			for (int i = 0; i < 100; i++) assertThat(seller.mutate("POST", "/api/sales", sale("1", "2")).statusCode()).isEqualTo(201);
			var first = admin.body(admin.get("/api/sales/reports/sales"));
			assertThat(first.get("totalElements").asLong()).isEqualTo(101);
			assertThat(first.get("rows").size()).isEqualTo(100);
			assertThat(first.get("totals").get(0).get("revenue").asText()).isEqualTo("402.00");
			assertThat(admin.body(admin.get("/api/sales/reports/sales?page=1")).get("rows").size()).isEqualTo(1);
			var staff = admin.body(admin.get("/api/sales/reports/staff-performance"));
			assertThat(staff.get("rows").get(0).get("receipts").asLong()).isEqualTo(101);
			assertLedgers();
		}
	}

	@Test
	void stockReportPaginationUsesStableGroupingIdentifiersForTiedRows() throws Exception {
		try (var admin = browser()) {
			fixture(admin);
			var operationId = UUID.randomUUID();
			var actorId = jdbc.queryForObject("SELECT id FROM staff_users WHERE username = 'admin'", UUID.class);
			var typeId = jdbc.queryForObject("SELECT id FROM produce_types WHERE code = 'ONION'", UUID.class);
			var unitId = UUID.fromString(unit("KG"));
			jdbc.update("""
				INSERT INTO stock_operations (id, branch_id, actor_id, kind, request_hash, posted_at)
				VALUES (?, ?, ?, 'RECEIPT', ?, CURRENT_TIMESTAMP)
				""", operationId, UUID.fromString(branch), actorId, "a".repeat(64));
			for (int index = 0; index < 101; index++) {
				var productId = UUID.randomUUID();
				jdbc.update("""
					INSERT INTO products (id, code, name, active, created_at, version, produce_type_id, base_unit_id)
					VALUES (?, ?, 'Shared name', TRUE, CURRENT_TIMESTAMP, 0, ?, ?)
					""", productId, "SHARED_" + index, typeId, unitId);
				jdbc.update("""
					INSERT INTO stock_batches (id, operation_id, branch_id, product_id, lot_code, received_date,
						input_unit_id, input_quantity, received_quantity, conversion_numerator, conversion_denominator,
						measured, quantity, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP)
					""", UUID.randomUUID(), operationId, UUID.fromString(branch), productId, "LOT-" + index, today,
					unitId, index + 1, index + 1, 1, 1, index + 1);
			}

			var firstPage = admin.body(admin.get("/api/sales/reports/stock-valuation"));
			var secondPage = admin.body(admin.get("/api/sales/reports/stock-valuation?page=1"));
			var quantities = new java.util.HashSet<String>();
			for (var row : firstPage.get("rows")) {
				assertThat(row.get("saleId").isNull()).isTrue();
				quantities.add(row.get("quantity").asText());
			}
			for (var row : secondPage.get("rows")) {
				assertThat(row.get("saleId").isNull()).isTrue();
				quantities.add(row.get("quantity").asText());
			}
			assertThat(firstPage.get("totalElements").asLong()).isEqualTo(101);
			assertThat(firstPage.get("rows").size()).isEqualTo(100);
			assertThat(secondPage.get("rows").size()).isEqualTo(1);
			assertThat(quantities).hasSize(101);
		}
	}

	@Test
	void concurrentSalesCannotOversellAndConcurrentVoidRetriesCannotDoubleRestore() throws Exception {
		try (var admin = browser(); var seller = browser(); var peer = browser(); var adminTwo = browser()) {
			fixture(admin);
			receive(admin, "100", "80", "AVAILABLE", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			peer.login("sale-two", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return seller.mutate("POST", "/api/sales", sale("75", "100")).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return peer.mutate("POST", "/api/sales", sale("75", "100")).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
			}
			assertBatch("AVAILABLE", "25", "2000");
			var id = jdbc.queryForObject("SELECT id FROM sales", UUID.class).toString();
			adminTwo.adminLogin();
			var request = voidRequest();
			try (var pool = Executors.newFixedThreadPool(2)) {
				var ready = new CountDownLatch(1);
				var one = pool.submit(() -> { ready.await(); return admin.mutate("POST", "/api/sales/" + id + "/void", request).statusCode(); });
				var two = pool.submit(() -> { ready.await(); return adminTwo.mutate("POST", "/api/sales/" + id + "/void", request).statusCode(); });
				ready.countDown();
				assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsOnly(200);
			}
			assertBatch("AVAILABLE", "100", "8000");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_operations WHERE kind = 'SALE_VOID'", Integer.class)).isEqualTo(1);
			assertLedgers();
		}
	}

	private void fixture(Browser admin) throws Exception {
		admin.adminLogin();
		branch = admin.body(admin.mutate("POST", "/api/branches", Map.of("code", "MAIN", "name", "Main branch", "active", true))).get("id").asText();
		otherBranch = admin.body(admin.mutate("POST", "/api/branches", Map.of("code", "OTHER", "name", "Other branch", "active", true))).get("id").asText();
		var type = admin.body(admin.mutate("POST", "/api/catalogue/types", Map.of("code", "ONION", "name", "Onions", "active", true))).get("id").asText();
		product = admin.body(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "ONION_KG", "name", "Red onions",
			"active", true, "produceTypeId", type, "baseUnitId", unit("KG")))).get("id").asText();
		approve(admin, "seller.one", "sale-one", List.of(branch));
		approve(admin, "seller.two", "sale-two", List.of(branch));
		approve(admin, "other.one", "other-one", List.of(otherBranch));
		approve(admin, "manager.one", "manager-one", List.of(branch));
	}

	@Test
	void monthlyDashboardUsesCompleteCalendarMonthsCurrenciesAndPermissionScopedSalespersonTotals() throws Exception {
		try (var admin = browser(); var seller = browser(); var colleague = browser(); var manager = browser()) {
			fixture(admin);
			receive(admin, "100", "80", "MONTHLY", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			colleague.login("sale-two", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var first = seller.body(seller.mutate("POST", "/api/sales", sale("1", "120"))).get("id").asText();
			var second = seller.body(seller.mutate("POST", "/api/sales", sale("1", "150"))).get("id").asText();
			var dollars = colleague.body(colleague.mutate("POST", "/api/sales", sale("1", "200"))).get("id").asText();
			var previous = seller.body(seller.mutate("POST", "/api/sales", sale("1", "100"))).get("id").asText();
			var excluded = seller.body(seller.mutate("POST", "/api/sales", sale("1", "800"))).get("id").asText();
			for (var id : List.of(first, second, dollars)) {
				jdbc.update("UPDATE sales SET sale_date = DATE '2024-02-29' WHERE id = ?", UUID.fromString(id));
			}

			jdbc.update("UPDATE sales SET branch_id = ? WHERE id = ?", UUID.fromString(otherBranch), UUID.fromString(second));
			jdbc.update("UPDATE sales SET currency = 'USD' WHERE id = ?", UUID.fromString(dollars));
			jdbc.update("UPDATE sales SET sale_date = DATE '2024-01-31' WHERE id = ?", UUID.fromString(previous));
			jdbc.update("UPDATE sales SET sale_date = DATE '2024-03-01' WHERE id = ?", UUID.fromString(excluded));
			var response = admin.get("/home?month=2024-02");
			assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
			assertThat(response.body()).contains("2024-02-01 to 2024-02-29", "2024-01-01 to 2024-01-31",
				"data-currency=\"KES\"", "value=\"USD\"", "data-value=\"270.00\"",
				"+170.0%", "data-cost=\"160.00\"", "data-profit=\"110.00\"",
				"data-day=\"1\" data-value=\"0\"", "data-day=\"29\" data-value=\"270.00\"");
			assertThat(response.body()).doesNotContain("data-value=\"800.00\"");
			assertThat(response.body()).contains("id=\"quantity-card\"", "data-quantity=\"2.000000\" data-unit=\"KG\"");
			var usd = admin.get("/home?month=2024-02&currency=USD").body();
			assertThat(usd).contains("data-currency=\"USD\"", "data-value=\"200.00\"").doesNotContain("data-value=\"270.00\"");
			assertThat(usd).contains("data-quantity=\"1.000000\" data-unit=\"KG\"");
			assertThat(admin.get("/home?month=2024-02&currency=NOT_A_CURRENCY").statusCode()).isEqualTo(400);
			manager.login("manager-one", List.of("SALES_VIEW"), OidcTestProvider.Fault.NONE);
			var scoped = manager.get("/home?month=2024-02").body();
			assertThat(scoped).contains("data-value=\"120.00\"", "value=\"USD\"")
				.doesNotContain("data-value=\"270.00\"", "data-cost=", "data-profit=", "monthly-costs", "data-chart=\"donut\"");
			assertThat(seller.get("/home?month=2024-02").statusCode()).isEqualTo(302);
			var own = seller.body(seller.get("/api/sales/reports/daily-sales?start=2024-02-01&end=2024-02-29"));
			assertThat(own.get("ownSalesOnly").asBoolean()).isTrue();
			assertThat(own.get("totals").get(0).get("revenue").asText()).isEqualTo("120.00");
			assertThat(own.get("totals").get(0).get("cost").isNull()).isTrue();
			assertThat(own.toString()).doesNotContain("USD", "seller.two");
			var empty = admin.get("/home?month=2024-04").body();
			assertThat(empty).contains("data-value=\"0\"", "No salesperson activity", "data-available=\"false\"");
		}
	}

	@Test
	void dashboardDoesNotTruncateSalespersonChartsAtTheReportPageSizeAndEscapesLabels() throws Exception {
		try (var admin = browser(); var seller = browser()) {
			fixture(admin);
			receive(admin, "10", "80", "CHARTS", null);
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var original = UUID.fromString(seller.body(seller.mutate("POST", "/api/sales", sale("1", "120"))).get("id").asText());
			for (int index = 0; index < 101; index++) {
				var user = UUID.randomUUID();
				var sale = UUID.randomUUID();
				var name = index == 100 ? "<script>chart-label</script>" : "Chart seller " + index;
				jdbc.update("""
					INSERT INTO staff_users(id, username, full_name, issuer, subject, active, all_branches, created_at)
					SELECT ?, ?, ?, issuer, ?, true, false, created_at FROM staff_users WHERE subject = 'sale-one'
					""", user, "chart.seller." + index, name, "chart-subject-" + index);
				jdbc.update("""
					INSERT INTO stock_operations(id, branch_id, actor_id, kind, request_hash, posted_at)
					SELECT ?, branch_id, ?, kind, request_hash, posted_at FROM stock_operations WHERE id = ?
					""", sale, user, original);
				jdbc.update("""
					INSERT INTO sales(id, branch_id, seller_id, seller_name, branch_name, currency, payment_method, total, cost, sale_date)
					SELECT ?, branch_id, ?, ?, branch_name, currency, payment_method, total, cost, sale_date FROM sales WHERE id = ?
					""", sale, user, name, original);
			}
			var month = java.time.YearMonth.from(today);
			var report = admin.body(admin.get("/api/sales/reports/staff-performance?start=" + month.atDay(1) + "&end=" + today));
			assertThat(report.get("rows").size()).isEqualTo(100);
			assertThat(report.get("totalElements").asInt()).isEqualTo(102);
			var dashboard = admin.get("/home").body();
			assertThat(dashboard).contains("data-amount=\"12240.00\"", "chart.seller.100", "&lt;script&gt;chart-label&lt;/script&gt;")
				.doesNotContain("<script>chart-label");
			assertThat(dashboard.split("data-label=", -1).length - 1).isEqualTo(102);
		}
	}

	@Test
	void quantitySoldCardSeparatesBaseUnitsAndSubtractsFullRefundsWithinSellerScope() throws Exception {
		try (var admin = browser(); var seller = browser(); var colleague = browser()) {
			fixture(admin);
			receive(admin, "10", "1", "QUANTITIES", null);
			var type = jdbc.queryForObject("SELECT produce_type_id FROM products WHERE id = ?", UUID.class, UUID.fromString(product));
			var counted = admin.body(admin.mutate("POST", "/api/catalogue/products", Map.of("code", "COUNTED",
				"name", "Counted produce", "active", true, "produceTypeId", type, "baseUnitId", unit("PIECE")))).get("id").asText();
			receiveLines(admin, List.of(Map.of("productId", counted, "unitId", unit("PIECE"), "quantity", "10",
				"purchaseUnitCost", "1", "lotCode", "COUNTED")));
			seller.login("sale-one", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			colleague.login("sale-two", List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
			var request = sale("2", "10");
			request.put("lines", List.of(saleLine("2", "10"), Map.of("productId", counted, "unitId", unit("PIECE"),
				"quantity", "3", "unitPrice", "10")));
			var posted = seller.mutate("POST", "/api/sales", request);
			assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
			assertThat(colleague.mutate("POST", "/api/sales", sale("1", "10")).statusCode()).isEqualTo(201);
			var dashboard = admin.get("/home").body();
			var card = dashboard.substring(dashboard.indexOf("id=\"quantity-card\"")).split("</article>", 2)[0];
			assertThat(card).contains("data-quantity=\"3.000000\" data-unit=\"KG\"",
				"data-quantity=\"3.000000\" data-unit=\"PIECE\"");
			var own = seller.body(seller.get("/api/sales/reports/product-sales")).get("rows");
			assertThat(own.size()).isEqualTo(2);
			for (var row : own) {
				assertThat(row.get("quantity").asText()).isEqualTo(row.get("unit").asText().equals("KG") ? "2.000000" : "3.000000");
			}
			var id = seller.body(posted).get("id").asText();
			assertThat(admin.mutate("POST", "/api/sales/" + id + "/void", voidRequest()).statusCode()).isEqualTo(200);
			dashboard = admin.get("/home").body();
			card = dashboard.substring(dashboard.indexOf("id=\"quantity-card\"")).split("</article>", 2)[0];
			assertThat(card).contains("data-quantity=\"1.000000\" data-unit=\"KG\"",
				"data-quantity=\"0.000000\" data-unit=\"PIECE\"");
		}
	}

	private void approve(Browser admin, String username, String subject, List<String> branchIds) throws Exception {
		assertThat(admin.mutate("POST", "/api/users", Map.of("username", username, "fullName", username,
			"subject", subject, "allBranches", false, "branchIds", branchIds)).statusCode()).isEqualTo(201);
	}

	private Map<String, Object> sale(String quantity, String price) {
		return new HashMap<>(Map.of("requestId", UUID.randomUUID().toString(), "paymentMethod", "CASH",
			"lines", List.of(saleLine(quantity, price))));
	}

	private Map<String, Object> saleLine(String quantity, String price) {
		return Map.of("productId", product, "unitId", unit("KG"), "quantity", quantity, "unitPrice", price);
	}

	private Map<String, Object> voidRequest() {
		return new HashMap<>(Map.of("requestId", UUID.randomUUID(), "reason", "All goods returned and full refund completed",
			"returnedAndRefunded", true, "refundReference", "REFUND-" + UUID.randomUUID()));
	}

	private void receive(Browser admin, String quantity, String price, String lot, LocalDate expiry) throws Exception {
		receiveLines(admin, List.of(receiptLine(quantity, price, lot, expiry)));
	}

	private Map<String, Object> receiptLine(String quantity, String price, String lot, LocalDate expiry) {
		var line = new HashMap<String, Object>(Map.of("productId", product, "unitId", unit("KG"),
			"quantity", quantity, "purchaseUnitCost", price, "lotCode", lot));
		if (expiry != null) line.put("expiryDate", expiry.toString());
		return line;
	}

	private void receiveLines(Browser admin, List<Map<String, Object>> lines) throws Exception {
		var response = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", UUID.randomUUID(),
			"branchId", branch, "receivedDate", today.minusDays(10).toString(), "source", "Farm", "currency", "KES", "lines", lines));
		assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
	}

	private String unit(String code) {
		return jdbc.queryForObject("SELECT id FROM units_of_measure WHERE code = ?", UUID.class, code).toString();
	}

	private void assertBatch(String lot, String quantity, String cost) {
		var row = jdbc.queryForMap("SELECT quantity, remaining_cost FROM stock_batches WHERE lot_code = ?", lot);
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
