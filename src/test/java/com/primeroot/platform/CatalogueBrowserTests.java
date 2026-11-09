package com.primeroot.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.primeroot.platform.access.StaffService;
import com.primeroot.platform.whatsapp.WhatsAppTransport;
import com.primeroot.platform.whatsapp.WhatsAppWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.browser", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false", "platform.inventory.currency=KES",
	"spring.datasource.url=jdbc:h2:mem:catalogue-browser-tests;MODE=PostgreSQL",
	"platform.whatsapp.enabled=true", "platform.whatsapp.worker-enabled=false",
	"platform.whatsapp.app-secret=0123456789abcdef0123456789abcdef",
	"platform.whatsapp.verify-token=test-only-verification-token-32-characters",
	"platform.whatsapp.access-token=test-only-meta-token", "platform.whatsapp.phone-number-id=123456789",
	"platform.whatsapp.business-number=254700000000", "platform.whatsapp.api-version=v24.0",
	"platform.whatsapp.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CatalogueBrowserTests extends OidcTestSupport {
	@Autowired
	private StaffService staff;
	@Autowired private WhatsAppWorker whatsappWorker;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
	@MockitoBean private WhatsAppTransport whatsappTransport;

	Path profile = Path.of("target", "browser-profiles", java.util.UUID.randomUUID().toString()).toAbsolutePath();

	@Test
	void producePickerUsesMatchingArtworkAndRetainsGenericFallback() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		try (var admin = browser()) {
			admin.adminLogin();
			var typeResponse = admin.mutate("POST", "/api/catalogue/types",
				Map.of("code", "UI_ARTWORK", "name", "UI Artwork", "active", true));
			assertThat(typeResponse.statusCode()).isEqualTo(201);
			var typeId = admin.body(typeResponse).get("id").asText();
			try {
				var products = new java.util.LinkedHashMap<String, String>();
				for (var entry : Map.of("Water Melon", "watermelon", "Green Capsicum", "capsicum",
						"Butternut", "butternut", "Fresh Produce", "produce").entrySet()) {
					var product = admin.mutate("POST", "/api/catalogue/products", Map.of(
						"code", "UI_ART_" + products.size(), "name", entry.getKey(), "produceTypeId", typeId,
						"baseUnitId", "10000000-0000-0000-0000-000000000001", "active", true));
					assertThat(product.statusCode()).isEqualTo(201);
					products.put(admin.body(product).get("id").asText(),
						"produce".equals(entry.getValue()) ? "produce" : "produce-" + entry.getValue());
				}
				try (var chrome = new Chrome()) {
					verifyPublicLogin(chrome);
					chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/stock-entries"));
					chrome.await("document.querySelector('.product-picker') !== null && document.readyState === 'complete'");
					for (var entry : products.entrySet()) {
						var card = "document.querySelector('[data-add-product][data-id=\"%s\"]')".formatted(entry.getKey());
						assertThat(chrome.evaluate(card + ".querySelector('use').getAttribute('href')").asText())
							.endsWith("#" + entry.getValue());
						chrome.evaluate(card + ".click(); true");
					}
					assertThat(chrome.evaluate("document.querySelectorAll('#receipt-lines .receipt-line').length").asInt())
						.isEqualTo(products.size());
					chrome.evaluate("""
						fetch('/assets/icons.svg').then(response => response.text()).then(text => {
							const sprite = new DOMParser().parseFromString(text, 'image/svg+xml');
							window.produceArtworkValid = sprite.querySelector('parsererror') === null &&
								['watermelon', 'capsicum', 'butternut'].every(type =>
									sprite.getElementById('produce-' + type)?.children.length > 0);
						}); true
						""");
					chrome.await("window.produceArtworkValid === true");
				}
			}
			finally {
				jdbc.update("DELETE FROM products WHERE produce_type_id = ?", java.util.UUID.fromString(typeId));
				jdbc.update("DELETE FROM produce_types WHERE id = ?", java.util.UUID.fromString(typeId));
			}
		}
	}

	@Test
	void revokedAndMissingSessionsSignInAgainWithoutPostingOrShowingSessionAlerts() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		PROVIDER.identity(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
		try (var chrome = new Chrome()) {
			verifyPublicLogin(chrome);
			for (boolean revoked : new boolean[] {true, false}) {
				chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/catalogue"));
				chrome.await("document.title.startsWith('Catalogue') && document.readyState === 'complete'");
				var oldToken = chrome.evaluate("document.querySelector('meta[name=\"csrf-token\"]').content").asText();
				if (revoked) {
					jdbc.update("UPDATE staff_users SET security_version = security_version + 1 WHERE subject = ?",
						OidcTestProvider.ADMIN_SUBJECT);
				}
				else {
					chrome.command("Network.clearBrowserCookies", Map.of());
				}
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="types"]');
					f.closest('details').open = true; f.elements.code.value = 'EXPIRED_SESSION';
					f.elements.name.value = 'Must not be posted'; f.requestSubmit(); return true; })()
					""");
				chrome.await("location.pathname === '/home' && document.readyState === 'complete'");
				assertThat(chrome.evaluate("document.body.innerText").asText())
					.doesNotContain("Your session has expired", "Request failed (403)");
				assertThat(chrome.evaluate("document.querySelector('meta[name=\"csrf-token\"]').content").asText())
					.isNotEqualTo(oldToken);
				try (var admin = browser()) {
					admin.adminLogin();
					assertThat(admin.get("/api/catalogue").body()).doesNotContain("EXPIRED_SESSION");
				}
			}
		}
	}

	@Test
	void permissionDeniedDoesNotLogOutAnAuthenticatedUser() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		PROVIDER.identity(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
		try (var chrome = new Chrome()) {
			verifyPublicLogin(chrome);
			chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/catalogue"));
			chrome.await("document.title.startsWith('Catalogue') && document.readyState === 'complete'");
			chrome.evaluate("""
				(() => { const original = window.fetch;
				window.fetch = (url, options) => String(url).endsWith('api/catalogue/types')
					? Promise.resolve(new Response(JSON.stringify({detail: 'Permission denied'}),
						{status: 403, headers: {'Content-Type': 'application/json'}}))
					: original(url, options);
				const f = document.querySelector('form[data-resource="types"]');
				f.closest('details').open = true; f.elements.code.value = 'DENIED';
				f.elements.name.value = 'Denied'; f.requestSubmit(); return true; })()
				""");
			chrome.await("document.querySelector('form[data-resource=\"types\"] .form-message').textContent === 'Permission denied'");
			assertThat(chrome.evaluate("location.pathname").asText()).isEqualTo("/ui/catalogue");
		}
	}

	@Test
	void catalogueAndInventoryFormsPostConsecutiveChangesWithExactDecimalsAndVisibleErrors() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		try (var session = browser()) {
			session.adminLogin();
			try (var chrome = new Chrome()) {
				chrome.command("Page.addScriptToEvaluateOnNewDocument", Map.of("source",
					"Object.defineProperty(globalThis.crypto, 'randomUUID', {value: undefined, configurable: true});"));
				verifyPublicLogin(chrome);
				assertThat(chrome.evaluate("typeof crypto.randomUUID === 'undefined' && typeof crypto.getRandomValues === 'function'")
					.asBoolean()).isTrue();
				chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/catalogue"));
				chrome.await("document.title.startsWith('Catalogue') && document.readyState === 'complete'");
				assertThat(chrome.evaluate("document.querySelector('form[data-resource=\"products\"] [name=\"gradeId\"]') === null").asBoolean()).isTrue();
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="types"]');
					f.closest('details').open = true; f.elements.code.value = 'UI_TOMATO';
					f.elements.name.value = 'UI Tomatoes'; f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('#types table').innerText.includes('UI Tomatoes')");
				chrome.ready();
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="products"]');
					f.closest('details').open = true; f.elements.code.value = 'UI_PRODUCT';
					f.elements.name.value = 'UI Tomato Product'; f.elements.produceTypeId.selectedIndex = 1;
					f.elements.baseUnitId.value = '10000000-0000-0000-0000-000000000001';
					f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('#products table').innerText.includes('UI Tomato Product')");
				chrome.ready();
				chrome.evaluate("document.querySelector('[data-edit=\"products\"]').click(); true");
				chrome.await("document.querySelector('form[data-resource=\"products\"]').elements.id.value !== ''");
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="products"]');
					f.elements.name.value = 'Edited UI Product'; f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('#products table')?.innerText.includes('Edited UI Product') === true && document.readyState === 'complete'");
				chrome.ready();
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="packaging"]');
					f.closest('details').open = true; f.elements.productId.selectedIndex = 1;
					f.elements.unitId.value = '10000000-0000-0000-0000-000000000004';
					f.elements.quantityInBaseUnit.value = '20.123456'; f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('#packaging table').innerText.includes('20.123456')");
				chrome.ready();
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="convert"]');
					f.elements.productId.selectedIndex = 1;
					f.elements.fromUnitId.value = '10000000-0000-0000-0000-000000000004';
					f.elements.quantity.value = '2'; f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('output').textContent === '40.246912 KG'");
				chrome.evaluate("""
					(() => { const f = document.querySelector('form[data-resource="types"]');
					f.closest('details').open = true; f.elements.code.value = 'UI_TOMATO';
					f.elements.name.value = 'Duplicate'; f.requestSubmit(); return true; })()
					""");
				chrome.await("document.querySelector('form[data-resource=\"types\"] .form-message').classList.contains('error')");
				var branchId = verifyBranchWorkspace(chrome, session);
				var productId = session.body(session.get("/api/catalogue")).get("products").get(0).get("id").asText();
				verifyInventoryWorkspace(chrome, session, branchId, productId);
				verifySalesWorkspace(chrome, session, branchId, productId);
				verifyTransferWorkspace(chrome, session, branchId, productId);
				verifyExpenseWorkspace(chrome, branchId);
				verifyDashboardWorkspace(chrome, session, branchId);
				verifyWhatsAppWorkspace(chrome);
				chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
					"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
				assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
				navigateSidebar(chrome, "stock", "/ui/stock");
				assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
				navigateSidebar(chrome, "reports", "/ui/reports/stock");
				assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
			}
		}
	}

	private void verifyDashboardWorkspace(Chrome chrome, Browser admin, String branchId) throws Exception {
		var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		var posted = admin.mutate("POST", "/api/expenses", Map.of("requestId", java.util.UUID.randomUUID(),
			"branchId", branchId, "paidDate", today.toString(), "category", "TRANSPORT", "description", "Dashboard transport",
			"amount", "25", "paymentMethod", "CASH"));
		assertThat(posted.statusCode()).withFailMessage(posted.body()).isEqualTo(201);
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1536, "height", 1040, "deviceScaleFactor", 1, "mobile", false));
		navigateSidebar(chrome, "dashboard", "/home");
		chrome.await("document.querySelectorAll('.dashboard-kpis > article').length === 6 && document.querySelectorAll('.chart-visual > svg').length === 3");
		chrome.await("[...document.querySelectorAll('.sidebar summary svg, .kpi-heading svg')].every(icon => icon.getBBox().width > 0)");
		assertThat(chrome.evaluate("document.querySelector('#expenses-card h2').textContent").asText()).isEqualTo("Total expenses");
		assertThat(chrome.evaluate("document.querySelector('#current-stock').innerText").asText()).contains("Live", "Edited UI Product");
		assertThat(chrome.evaluate("document.querySelector('.dashboard-details').open").asBoolean()).isFalse();
		assertThat(chrome.evaluate("getComputedStyle(document.querySelector('.dashboard-kpis')).gridTemplateColumns.split(' ').length").asInt()).isEqualTo(6);
		assertThat(chrome.evaluate("document.querySelector('#quantity-card').innerText").asText()).contains("Quantity sold", "KG");
		assertThat(chrome.evaluate("getComputedStyle(document.querySelector('.dashboard-chart-grid')).gridTemplateColumns.split(' ').length").asInt()).isEqualTo(4);
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		var screenshot = System.getProperty("ui.screenshot");
		if (screenshot != null) {
			var capture = chrome.command("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true));
			Files.write(Path.of(screenshot), java.util.Base64.getDecoder().decode(capture.get("data").asText()));
		}
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.command("Emulation.clearDeviceMetricsOverride", Map.of());
	}

	private void verifyWhatsAppWorkspace(Chrome chrome) throws Exception {
		navigateSidebar(chrome, "whatsapp", "/ui/whatsapp");
		chrome.evaluate("document.getElementById('whatsapp-create').click(); true");
		chrome.await("!document.getElementById('whatsapp-code').hidden");
		var command = chrome.evaluate("document.getElementById('whatsapp-command').textContent").asText();
		assertThat(command).startsWith("LINK ").hasSize(48);
		var bytes = json.writeValueAsBytes(WhatsAppIntegrationTests.payload("browser-link", "254722222222", command, "123456789"));
		try (var client = HttpClient.newHttpClient()) {
			var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/whatsapp/webhook"))
				.header("Content-Type", "application/json").header("X-Hub-Signature-256", WhatsAppIntegrationTests.signature(bytes))
				.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
		}
		assertThat(whatsappWorker.processOne()).isTrue();
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/whatsapp"));
		chrome.await("document.getElementById('whatsapp-confirm') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.body.innerText").asText()).contains("+254722222222");
		chrome.evaluate("document.getElementById('whatsapp-confirm').click(); true");
		chrome.await("document.body.innerText.includes('Linked WhatsApp number:') && document.getElementById('whatsapp-confirm') === null");
		chrome.ready();
		chrome.evaluate("document.getElementById('whatsapp-unlink').click(); true");
		chrome.await("document.body.innerText.includes('No WhatsApp account linked.') && document.getElementById('whatsapp-unlink') === null");
	}

	private void verifyPublicLogin(Chrome chrome) throws Exception {
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/"));
		chrome.await("location.pathname === '/home' && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('.brand').textContent").asText()).isEqualTo("Prime Root");
	}

	private String verifyBranchWorkspace(Chrome chrome, Browser admin) throws Exception {
		navigateSidebar(chrome, "configurations", "/ui/branches");
		assertThat(chrome.evaluate("document.querySelector('#branches-editor').open").asBoolean()).isTrue();
		var create = """
			(() => { const f = document.querySelector('form[data-resource="branches"]');
			f.elements.code.value = 'ui_stock'; f.elements.name.value = 'UI Stock Branch';
			f.elements.address.value = 'Market Road'; f.requestSubmit(); return true; })()
			""";
		chrome.evaluate(create);
		chrome.await("document.querySelector('#branches table').innerText.includes('UI_STOCK') && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#notice').textContent").asText()).isEqualTo("Branch changes saved.");
		var id = chrome.evaluate("document.querySelector('[data-edit=\"branches\"]').dataset.id").asText();
		chrome.evaluate(create);
		chrome.await("document.querySelector('form[data-resource=\"branches\"] .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('form[data-resource=\"branches\"] .form-message').textContent").asText())
			.contains("code already exists");
		var edit = "document.querySelector('[data-edit=\"branches\"][data-id=\"" + id + "\"]').click(); true";
		chrome.evaluate(edit);
		chrome.await("document.querySelector('form[data-resource=\"branches\"]').elements.id.value !== ''");
		var version = chrome.evaluate("document.querySelector('form[data-resource=\"branches\"]').elements.version.value").asText();
		assertThat(admin.mutateWithIfMatch("PUT", "/api/branches/" + id, Map.of(
			"code", "UI_STOCK", "name", "UI Stock Branch", "address", "Changed by another administrator", "active", true),
			"\"" + version + "\"").statusCode()).isEqualTo(200);
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-resource="branches"]');
			f.elements.address.value = 'Stale address'; f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('form[data-resource=\"branches\"] .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('form[data-resource=\"branches\"] .form-message').textContent").asText())
			.contains("branch changed");
		chrome.evaluate(edit);
		chrome.await("document.querySelector('form[data-resource=\"branches\"]').elements.address.value === 'Changed by another administrator'");
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-resource="branches"]');
			f.elements.address.value = 'Updated Market Road'; f.elements.active.checked = false;
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#branches table').innerText.includes('Inactive') && document.readyState === 'complete'");
		chrome.ready();
		chrome.evaluate(edit);
		chrome.await("document.querySelector('form[data-resource=\"branches\"]').elements.id.value !== ''");
		assertThat(chrome.evaluate("document.querySelector('form[data-resource=\"branches\"]').elements.active.checked").asBoolean()).isFalse();
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-resource="branches"]');
			f.elements.active.checked = true; f.requestSubmit(); return true; })()
			""");
		chrome.await("!document.querySelector('#branches table').innerText.includes('Inactive') && document.querySelector('form[data-resource=\"branches\"]').elements.id.value === '' && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#branches table').innerText").asText()).contains("Updated Market Road");
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
		return id;
	}

	private void verifySalesWorkspace(Chrome chrome, Browser admin, String branchId, String productId) throws Exception {
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
		verifyStaffAssignments(chrome, admin, branchId);
		loginChrome(chrome, "ui-sales-subject", java.util.List.of("BRANCH_VIEW", "BRANCH_MANAGE"), "UI Salesperson");
		navigateSidebar(chrome, "configurations", "/ui/branches");
		assertThat(chrome.evaluate("document.querySelector('#branches-editor').hidden").asBoolean()).isTrue();
		chrome.evaluate("document.querySelector('[data-edit=\"branches\"]').click(); true");
		chrome.await("!document.querySelector('#branches-editor').hidden && document.querySelector('form[data-resource=\"branches\"]').elements.id.value !== ''");
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-resource="branches"]');
			f.elements.address.value = 'Scoped manager address'; f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#branches table').innerText.includes('Scoped manager address') && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#branches-editor').hidden").asBoolean()).isTrue();
		loginChrome(chrome, "ui-sales-subject", java.util.List.of("SALE_CREATE"), "UI Salesperson");
		assertThat(chrome.evaluate("location.pathname").asText()).isEqualTo("/ui/sales");
		navigateSidebar(chrome, "sales", "/ui/sales");
		assertThat(chrome.evaluate("""
			[...document.querySelectorAll('.sidebar .nav-group')].map(group => group.id).join(',')
			""").asText()).isEqualTo("nav-whatsapp,nav-sales,nav-reports");
		assertThat(chrome.evaluate("document.querySelector('#sale-customer').value").asText()).isEqualTo("Walk-ins");
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.saleDate.value").asText())
			.isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
		assertThat(chrome.evaluate("document.querySelector('#sale-form [name=\"branchId\"]') === null").asBoolean()).isTrue();
		assertThat(chrome.evaluate("getComputedStyle(document.querySelector('.sale-entry-layout')).gridTemplateColumns.split(' ').length").asInt()).isEqualTo(2);
		assertThat(chrome.evaluate("document.querySelector('.product-art').getBoundingClientRect().width").asDouble()).isGreaterThan(90);
		assertThat(chrome.evaluate("document.querySelector('.page-header').innerText").asText()).doesNotContain("automatically", "Phase");
		chrome.evaluate("""
			(() => { const search = document.querySelector('#product-search');
			search.value = 'no-product-matches-this'; search.dispatchEvent(new Event('input')); return true; })()
			""");
		assertThat(chrome.evaluate("document.querySelector('#product-empty').hidden").asBoolean()).isFalse();
		assertThat(chrome.evaluate("document.querySelector('[data-add-product]').getBoundingClientRect().width").asDouble()).isZero();
		chrome.evaluate("""
			(() => { const card = document.querySelector('[data-add-product][data-id="%s"]');
			const search = document.querySelector('#product-search'); search.value = card.dataset.name;
			search.dispatchEvent(new Event('input'));
			document.querySelector(`[data-product-filter="${card.dataset.type}"]`).click();
			card.click(); card.click(); return true; })()
			""".formatted(productId));
		assertThat(chrome.evaluate("document.querySelectorAll('#sale-lines .sale-line').length").asInt()).isEqualTo(1);
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.quantity.value").asText()).isEqualTo("2");
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.unitId.value").asText())
			.isEqualTo("10000000-0000-0000-0000-000000000001");
		chrome.evaluate("""
			document.querySelector('#sale-form').elements.quantity.value = '2.500001';
			document.querySelector('[data-quantity-change="1"]').click(); true
			""");
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.quantity.value").asText()).isEqualTo("3.500001");
		chrome.evaluate("document.querySelector('[data-quantity-change=\"-1\"]').click(); true");
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.quantity.value").asText()).isEqualTo("2.500001");
		chrome.evaluate("""
			document.querySelector('#add-sale-line').click();
			document.querySelectorAll('[data-remove-sale-line]')[1].click(); true
			""");
		assertThat(chrome.evaluate("document.querySelectorAll('#sale-lines .sale-line').length").asInt()).isEqualTo(1);
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form');
			f.elements.productId.value = '%s'; f.elements.unitId.value = '10000000-0000-0000-0000-000000000001';
			f.elements.quantity.value = '99'; f.elements.unitPrice.value = '120';
			document.querySelector('#quote-sale').click(); return true; })()
			""".formatted(productId));
		chrome.await("document.querySelector('#sale-total').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#sale-total').textContent").asText()).contains("Insufficient");
		chrome.evaluate("""
			document.querySelector('#sale-form').elements.quantity.value = '1.25';
			document.querySelector('#sale-form').elements.quantity.dispatchEvent(new Event('input', {bubbles: true})); true
			""");
		chrome.await("document.querySelector('#sale-total').textContent.includes('Total: KES 150.00')");
		assertThat(chrome.evaluate("document.querySelector('.line-total').textContent").asText()).isEqualTo("KES 150.00");
		assertThat(chrome.evaluate("document.querySelector('#sale-count').textContent").asText()).isEqualTo("1 item");
		var saleScreenshot = System.getProperty("ui.sale-screenshot");
		if (saleScreenshot != null) {
			var capture = chrome.command("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true));
			Files.write(Path.of(saleScreenshot), java.util.Base64.getDecoder().decode(capture.get("data").asText()));
		}
		chrome.evaluate("document.querySelector('#sale-form').requestSubmit(); true");
		assertThat(chrome.evaluate("location.pathname").asText()).isEqualTo("/ui/sales");
		chrome.evaluate("""
			const form = document.querySelector('#sale-form'); form.elements.paymentConfirmed.checked = true;
			form.requestSubmit(); true
			""");
		chrome.await("document.querySelector('#sale-receipt') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('#sale-receipt').innerText").asText()).contains("Walk-ins", "KES 150.00", "PAID");
		var firstReceipt = chrome.evaluate("location.pathname").asText();
		navigateSidebar(chrome, "sales", "/ui/sales");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form'); f.elements.customer.value = '__new__';
			f.elements.customer.dispatchEvent(new Event('change')); f.elements.customerName.value = 'UI Grocery Customer';
			f.elements.productId.value = '%s'; f.elements.unitId.value = '10000000-0000-0000-0000-000000000001';
			f.elements.quantity.value = '.75'; f.elements.unitPrice.value = '120';
			document.querySelector('[data-payment="MPESA"]').click(); f.elements.paymentReference.value = 'UI-MPESA-001';
			document.querySelector('#quote-sale').click(); return true; })()
			""".formatted(productId));
		chrome.await("document.querySelector('#sale-total').textContent.includes('Total: KES 90.00')");
		assertThat(chrome.evaluate("document.querySelector('#payment-reference-label').hidden").asBoolean()).isFalse();
		assertThat(chrome.evaluate("document.querySelector('#sale-form').elements.paymentReference.required").asBoolean()).isTrue();
		assertThat(chrome.evaluate("document.querySelector('[data-payment=\"MPESA\"]').getAttribute('aria-pressed')").asText()).isEqualTo("true");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form'); f.elements.paymentConfirmed.checked = true;
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#sale-receipt') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('#sale-receipt').innerText").asText())
			.contains("UI Grocery Customer", "UI-MPESA-001", "KES 90.00");
		navigateSidebar(chrome, "sales", "/ui/sales");
		assertThat(chrome.evaluate("document.querySelector('#sale-customer').value").asText()).isEqualTo("Walk-ins");
		assertThat(chrome.evaluate("document.querySelector('#sale-customer').innerText").asText()).contains("UI Grocery Customer");
		assertThat(chrome.evaluate("document.querySelectorAll('#nav-stock a[href^=\"/ui/reports/\"]').length").asInt()).isZero();
		assertThat(chrome.evaluate("document.querySelector('#nav-reports a[href=\"/ui/reports/profit\"]') === null").asBoolean()).isTrue();
		for (var report : java.util.List.of("sales", "daily-sales", "product-sales", "branch-sales",
				"staff-performance", "customer-sales", "payments")) {
			navigateSidebar(chrome, "reports", "/ui/reports/" + report);
			assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("Your own attributed sales only", "240.00");
		}
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		navigateSidebar(chrome, "sales", "/ui/sales");
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.evaluate("document.querySelector('[data-add-product][data-id=\"%s\"]').click(); true".formatted(productId));
		assertThat(chrome.evaluate("""
			(() => { const field = document.querySelector('#sale-form').elements.unitPrice;
			return document.activeElement === field && field.getBoundingClientRect().top >= 0
			  && field.getBoundingClientRect().bottom <= innerHeight; })()
			""").asBoolean()).isTrue();
		navigateSidebar(chrome, "reports", "/ui/reports/staff-performance");
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		loginChrome(chrome, OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, "Browser Administrator");
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + firstReceipt));
		chrome.await("document.querySelector('#sale-void-form') !== null && document.readyState === 'complete'");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-void-form'); f.elements.reason.value = 'UI full sale returned';
			f.elements.refundReference.value = 'UI-REFUND-001'; f.elements.returnedAndRefunded.checked = true;
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#sale-receipt') !== null && document.querySelector('#sale-receipt').innerText.includes('VOIDED') && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('#sale-void-form') === null").asBoolean()).isTrue();
		for (var report : java.util.List.of("profit", "stock-valuation", "stock-losses", "staff-performance")) {
			navigateSidebar(chrome, "reports", "/ui/reports/" + report);
			assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		}
		navigateSidebar(chrome, "reports", "/ui/reports/profit");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("90.00", "60.09", "29.91");
		navigateSidebar(chrome, "dashboard", "/home");
		assertThat(chrome.evaluate("document.querySelector('#sales-card').dataset.amount").asText()).isEqualTo("90.00");
		assertThat(chrome.evaluate("document.querySelector('#staff-sales-card').innerText").asText()).contains("Sales by salesperson");
		chrome.await("document.querySelectorAll('.chart-visual > svg').length === 3");
		assertThat(chrome.evaluate("document.querySelector('#dashboard-month').value").asText())
			.isEqualTo(java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString());
		assertThat(chrome.evaluate("document.querySelector('[data-chart=\"donut\"] .chart-visual svg').textContent").asText())
			.contains("Net sales", "COGS", "gross profit");
		chrome.evaluate("document.querySelector('#dashboard-month').value = '2024-02'; document.querySelector('#dashboard-month').dispatchEvent(new Event('change')); true");
		chrome.await("new URLSearchParams(location.search).get('month') === '2024-02' && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('#monthly-insights').innerText").asText())
			.contains("No sales in either period");
		chrome.evaluate("document.querySelector('[data-current-month]').click(); true");
		chrome.await("location.search === '' && document.querySelectorAll('.chart-visual > svg').length === 3 && document.readyState === 'complete'");
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.command("Emulation.clearDeviceMetricsOverride", Map.of());
		var saleDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
		var recoveredStock = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", java.util.UUID.randomUUID(),
			"branchId", branchId, "receivedDate", saleDate, "source", "Downtime receipt", "currency", "KES",
			"lines", java.util.List.of(Map.of("productId", productId, "unitId", "10000000-0000-0000-0000-000000000001",
				"quantity", "1", "purchaseUnitCost", "10", "lotCode", "UI_BACKDATED",
				"expiryDate", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()))));
		assertThat(recoveredStock.statusCode()).withFailMessage(recoveredStock.body()).isEqualTo(201);
		loginChrome(chrome, "ui-sales-subject", java.util.List.of("SALE_CREATE"), "UI Salesperson");
		navigateSidebar(chrome, "sales", "/ui/sales");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form');
			f.elements.productId.value = '%s'; f.elements.unitId.value = '10000000-0000-0000-0000-000000000001';
			f.elements.quantity.value = '0.5'; f.elements.unitPrice.value = '120';
			document.querySelector('#quote-sale').click(); return true; })()
			""".formatted(productId));
		chrome.await("document.querySelector('#sale-total').textContent.includes('Total: KES 60.00')");
		var changedDate = chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form'); f.elements.paymentConfirmed.checked = true;
			f.elements.saleDate.value = '%s'; f.elements.saleDate.dispatchEvent(new Event('input'));
			return {confirmed: f.elements.paymentConfirmed.checked, total: document.querySelector('#sale-total').textContent}; })()
			""".formatted(saleDate));
		assertThat(changedDate.get("confirmed").asBoolean()).isFalse();
		assertThat(changedDate.get("total").asText()).contains("Calculate the total");
		chrome.evaluate("document.querySelector('#quote-sale').click(); true");
		chrome.await("document.querySelector('#sale-total').textContent.includes('Total: KES 60.00')");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-form'); f.elements.paymentConfirmed.checked = true;
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#sale-receipt') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('#sale-receipt').innerText").asText())
			.contains("Sale date (UTC): " + saleDate, "Recorded at:", "UI_BACKDATED");
		var recoveredReport = admin.body(admin.get("/api/sales/reports/sales?start=" + saleDate + "&end=" + saleDate));
		assertThat(recoveredReport.get("totals").get(0).get("revenue").asText()).isEqualTo("60.00");
		var historicalReceipt = chrome.evaluate("location.pathname").asText();
		assertThat(chrome.evaluate("document.querySelector('#sale-date-form') === null").asBoolean()).isTrue();
		loginChrome(chrome, OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, "Browser Administrator");
		navigateSidebar(chrome, "sales", "/ui/sales/edit");
		assertThat(chrome.evaluate("document.querySelector('#sale-week-filter').elements.week.value").asText())
			.isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC)
				.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString());
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-week-filter');
			f.elements.week.value = '%s'; f.requestSubmit(); return true; })()
			""".formatted(saleDate));
		chrome.await("new URLSearchParams(location.search).get('week') === '%s' && document.readyState === 'complete'".formatted(saleDate));
		chrome.evaluate("document.querySelector('#editable-sales a[href=\"%s#date-correction\"]').click(); true".formatted(historicalReceipt));
		chrome.await("document.querySelector('#sale-date-form') !== null && document.querySelector('#date-correction').open && document.readyState === 'complete'");
		var correctedDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		chrome.evaluate("""
			(() => { const f = document.querySelector('#sale-date-form');
			f.closest('details').open = true; f.elements.saleDate.value = '%s';
			f.elements.reason.value = 'UI historical sale date corrected'; f.requestSubmit(); return true; })()
			""".formatted(correctedDate));
		chrome.await("document.querySelector('#sale-date')?.textContent === '%s' && document.readyState === 'complete'".formatted(correctedDate));
		assertThat(chrome.evaluate("document.querySelector('#sale-receipt').textContent").asText())
			.contains("UI historical sale date corrected", "UI_BACKDATED", "KES 60.00");
		assertThat(admin.body(admin.get("/api/sales/reports/sales?start=" + saleDate + "&end=" + saleDate))
			.get("totals").size()).isZero();
		loginChrome(chrome, OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, "Browser Administrator");
	}

	private void verifyStaffAssignments(Chrome chrome, Browser admin, String branchId) throws Exception {
		chrome.command("Network.clearBrowserCookies", Map.of());
		PROVIDER.identity("ui-sales-subject", java.util.List.of("SALE_CREATE"), OidcTestProvider.Fault.NONE);
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/home"));
		chrome.await("location.pathname === '/access-pending' && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Awaiting administrator approval");
		loginChrome(chrome, OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, "Browser Administrator");
		navigateSidebar(chrome, "users", "/ui/users");
		assertThat(chrome.evaluate("document.querySelector('#staff-form').elements.scope.value").asText()).isEqualTo("single");
		chrome.evaluate("document.querySelector('[data-review-access]').click(); true");
		chrome.await("document.querySelector('#staff-form').elements.accessRequestId.value !== ''");
		assertThat(chrome.evaluate("document.querySelector('#staff-form').elements.subject.value").asText()).isEqualTo("ui-sales-subject");
		assertThat(chrome.evaluate("document.querySelector('#staff-form').elements.subject.readOnly").asBoolean()).isTrue();
		var approve = """
			(() => { const f = document.querySelector('#staff-form');
			f.elements.username.value = 'ui.sales'; f.elements.fullName.value = 'UI Salesperson';
			f.elements.branchIds.value = '%s';
			f.requestSubmit(); return true; })()
			""".formatted(branchId);
		chrome.evaluate(approve);
		chrome.await("document.querySelector('#staff-directory').innerText.includes('UI Salesperson') && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#notice').textContent").asText()).contains("branch assignments saved");
		assertThat(chrome.evaluate("document.querySelectorAll('[data-review-access]').length").asInt()).isZero();
		chrome.evaluate("document.querySelector('#staff-form').elements.subject.value = 'ui-sales-subject'; true");
		chrome.evaluate(approve);
		chrome.await("document.querySelector('#staff-form .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#staff-form .form-message').textContent").asText()).contains("already approved");
		var editSeller = """
			[...document.querySelectorAll('#staff-directory tbody tr')]
			  .find(row => row.cells[0].textContent === 'ui.sales').querySelector('[data-edit-user]').click(); true
			""";
		chrome.evaluate(editSeller);
		chrome.await("document.querySelector('#staff-form').elements.id.value !== ''");
		assertThat(chrome.evaluate("""
			(() => { const f = document.querySelector('#staff-form');
			return f.elements.username.readOnly && f.elements.subject.readOnly && !f.elements.active.disabled; })()
			""").asBoolean()).isTrue();
		var otherBranch = admin.mutate("POST", "/api/branches", Map.of(
			"code", "UI_OTHER", "name", "UI Other Branch", "active", true));
		assertThat(otherBranch.statusCode()).isEqualTo(201);
		var otherId = admin.body(otherBranch).get("id").asText();
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/users"));
		chrome.await("document.querySelector('#staff-form') !== null && document.readyState === 'complete'");
		chrome.evaluate(editSeller);
		chrome.await("document.querySelector('#staff-form').elements.id.value !== ''");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#staff-form');
			f.elements.scope.value = 'multiple'; f.elements.scope.dispatchEvent(new Event('change'));
			[...f.elements.branchIds.options].forEach(option => { option.selected = ['%s', '%s'].includes(option.value); });
			f.requestSubmit(); return true; })()
			""".formatted(branchId, otherId));
		chrome.await("document.querySelector('#staff-directory').innerText.includes('2 assigned branches') && document.readyState === 'complete'");
		chrome.ready();
		chrome.evaluate(editSeller);
		chrome.await("document.querySelector('#staff-form').elements.id.value !== ''");
		assertThat(chrome.evaluate("document.querySelector('#staff-form').elements.branchIds.selectedOptions.length").asInt()).isEqualTo(2);
		chrome.evaluate("""
			(() => { const f = document.querySelector('#staff-form');
			f.elements.scope.value = 'single'; f.elements.scope.dispatchEvent(new Event('change'));
			return f.elements.branchIds.value; })()
			""");
		assertThat(chrome.evaluate("document.querySelector('#staff-form').elements.branchIds.value").asText()).isEmpty();
		chrome.evaluate("""
			(() => { const f = document.querySelector('#staff-form'); f.elements.branchIds.value = '%s';
			f.requestSubmit(); return true; })()
			""".formatted(otherId));
		chrome.await("document.querySelector('#staff-directory').innerText.includes('1 assigned branches') && !document.querySelector('#staff-directory').innerText.includes('2 assigned branches') && document.readyState === 'complete'");
		chrome.ready();
		chrome.evaluate(editSeller);
		chrome.await("document.querySelector('#staff-form').elements.id.value !== ''");
		var userId = chrome.evaluate("document.querySelector('#staff-form').elements.id.value").asText();
		var version = chrome.evaluate("document.querySelector('#staff-form').elements.version.value").asText();
		assertThat(admin.mutateWithIfMatch("PUT", "/api/users/" + userId, Map.of(
			"fullName", "Changed by another administrator", "active", true, "allBranches", false,
			"branchIds", java.util.List.of(otherId)), "\"" + version + "\"").statusCode()).isEqualTo(200);
		chrome.evaluate("document.querySelector('#staff-form').requestSubmit(); true");
		chrome.await("document.querySelector('#staff-form .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#staff-form .form-message').textContent").asText()).contains("record changed");
		chrome.evaluate(editSeller);
		chrome.await("document.querySelector('#staff-form').elements.fullName.value === 'Changed by another administrator'");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#staff-form'); f.elements.fullName.value = 'UI Salesperson';
			f.elements.branchIds.value = '%s'; f.requestSubmit(); return true; })()
			""".formatted(branchId));
		chrome.await("document.querySelector('#staff-directory').innerText.includes('UI Salesperson') && document.querySelector('#staff-form').elements.id.value === '' && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("""
			[...document.querySelectorAll('#staff-directory tbody tr')]
			  .find(row => row.cells[0].textContent === 'ui.sales').cells[3].textContent
			""").asText()).contains("UI Stock Branch").doesNotContain("UI Other Branch");
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
	}

	private void verifyTransferWorkspace(Chrome chrome, Browser admin, String branchId, String productId) throws Exception {
		var destinationResponse = admin.mutate("POST", "/api/branches",
			Map.of("code", "UI_TRANSFER_DEST", "name", "UI Transfer Destination", "active", true));
		assertThat(destinationResponse.statusCode()).withFailMessage(destinationResponse.body()).isEqualTo(201);
		var destinationId = admin.body(destinationResponse).get("id").asText();
		var stock = admin.mutate("POST", "/api/inventory/receipts", Map.of("requestId", java.util.UUID.randomUUID(),
			"branchId", branchId, "receivedDate", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
			"source", "UI transfer farm", "currency", "KES", "lines", java.util.List.of(Map.of("productId", productId,
				"unitId", "10000000-0000-0000-0000-000000000001", "quantity", "20", "purchaseUnitCost", "10", "lotCode", "UI_TRANSFER"))));
		assertThat(stock.statusCode()).withFailMessage(stock.body()).isEqualTo(201);
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
		navigateSidebar(chrome, "transfers", "/ui/transfers");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#transfer-dispatch');
			f.elements.sourceId.value = '%s'; f.elements.destinationId.value = '%s';
			f.elements.productId.value = '%s'; f.elements.quantity.value = '999';
			f.requestSubmit(); return true; })()
			""".formatted(branchId, destinationId, productId));
		chrome.await("document.querySelector('#notice').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#notice').textContent").asText()).contains("Insufficient");
		chrome.evaluate("""
			const transferForm = document.querySelector('#transfer-dispatch');
			transferForm.elements.quantity.value = '2'; transferForm.requestSubmit(); true
			""");
		chrome.await("document.querySelector('#transfer-receive') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("IN_TRANSIT", "UI Transfer Destination");
		chrome.evaluate("""
			(() => { const rows = [...document.querySelectorAll('.transfer-arrival')];
			rows.forEach((row, index) => {
			  row.querySelector('[name="receivedQuantity"]').value = index === 0 ? '0' : row.dataset.quantity;
			  row.querySelector('[name="shortageQuantity"]').value = index === 0 ? row.dataset.quantity : '0';
			});
			const f = document.querySelector('#transfer-receive'); f.querySelector('[type="checkbox"]').checked = true;
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#notice').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#notice').textContent").asText()).contains("reason");
		chrome.evaluate("""
			document.querySelector('.transfer-arrival [name="reason"]').value = 'UI full allocation loss';
			document.querySelector('#transfer-receive').requestSubmit(); true
			""");
		chrome.await("document.querySelector('#transfer-receive') === null && document.querySelector('main').innerText.includes('RECEIVED') && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("UI full allocation loss");
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		navigateSidebar(chrome, "transfers", "/ui/transfers");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("RECEIVED", "UI Transfer Destination");
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
	}

	private void verifyExpenseWorkspace(Chrome chrome, String branchId) throws Exception {
		var paidDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(10).toString();
		navigateSidebar(chrome, "expenses", "/ui/expenses");
		chrome.evaluate("""
			(() => { const f = document.querySelector('#expense-form');
			f.elements.branchId.value = '%s'; f.elements.category.value = 'TRANSPORT';
			f.elements.paidDate.value = '%s';
			f.elements.description.value = 'UI fuel expense'; f.elements.amount.value = '12.34';
			f.elements.paymentMethod.value = 'BANK'; f.elements.paymentMethod.dispatchEvent(new Event('change'));
			f.elements.paymentReference.value = 'UI-EXPENSE-001'; f.requestSubmit(); return true; })()
			""".formatted(branchId, paidDate));
		chrome.await("document.querySelector('table').innerText.includes('UI fuel expense') && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('table').innerText").asText()).contains("12.34", "UI-EXPENSE-001");
		assertThat(chrome.evaluate("new URLSearchParams(location.search).get('start')").asText()).isEqualTo(paidDate);
		navigateSidebar(chrome, "reports", "/ui/reports/net-profit");
		chrome.evaluate("""
			(() => { const f = document.querySelector('.date-filter'); f.elements.start.value = '%s';
			f.requestSubmit(); return true; })()
			""".formatted(paidDate));
		chrome.await("document.readyState === 'complete' && document.querySelector('main').innerText.includes('12.34')");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("12.34", "Known stock losses");
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		navigateSidebar(chrome, "expenses", "/ui/expenses");
		chrome.evaluate("""
			(() => { const f = document.querySelector('.date-filter'); f.elements.start.value = '%s';
			f.requestSubmit(); return true; })()
			""".formatted(paidDate));
		chrome.await("document.readyState === 'complete' && document.querySelector('.expense-void') !== null");
		chrome.evaluate("""
			(() => { const f = document.querySelector('.expense-void');
			f.elements.reason.value = 'UI entry correction'; f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('table').innerText.includes('VOIDED') && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('table').innerText").asText()).contains("UI entry correction");
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		navigateSidebar(chrome, "reports", "/ui/reports/net-profit");
		chrome.evaluate("""
			(() => { const f = document.querySelector('.date-filter'); f.elements.start.value = '%s';
			f.requestSubmit(); return true; })()
			""".formatted(paidDate));
		chrome.await("document.readyState === 'complete' && document.querySelector('main').innerText.includes('Net expenses: 0.00')");
		assertThat(chrome.evaluate("document.querySelector('main').innerText").asText()).contains("Net expenses: 0.00");
	}

	private void loginChrome(Chrome chrome, String subject, java.util.List<String> permissions, String name) throws Exception {
		chrome.command("Network.clearBrowserCookies", Map.of());
		PROVIDER.identity(subject, permissions, OidcTestProvider.Fault.NONE);
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/home"));
		chrome.await("['/home', '/ui/sales'].includes(location.pathname) && document.readyState === 'complete' && "
			+ "document.querySelector('.sidebar-foot strong')?.textContent === " + json.writeValueAsString(name));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "KEYCLOAK_THEME_VISUAL_TEST_URL", matches = ".+")
	void keycloakBrandIsReadableAndNetworkTaglineAlignsWithLoginFields() throws Exception {
		var url = System.getenv("KEYCLOAK_THEME_VISUAL_TEST_URL");
		assertThat(URI.create(url).getHost()).isIn("localhost", "127.0.0.1");
		try (var chrome = new Chrome()) {
			chrome.command("Page.navigate", Map.of("url", url));
			chrome.await("document.querySelector('#kc-form-login') !== null && document.readyState === 'complete'");
			assertThat(chrome.evaluate("document.querySelector('#prime-root-heading').innerText").asText())
				.contains("Fresh produce.", "Great connections.").doesNotContain("Good produce.");
			for (var width : new int[] {1440, 2000}) {
				chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
					"width", width, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
				assertThat(chrome.evaluate("""
					(() => {
					  const luminance = color => color.match(/[\\d.]+/g).slice(0, 3)
					    .map(value => Number(value) / 255)
					    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
					    .reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
					  const brand = luminance(getComputedStyle(document.querySelector('#kc-header-wrapper')).color);
					  const background = luminance(getComputedStyle(document.body).backgroundColor);
					  return (Math.max(brand, background) + .05) / (Math.min(brand, background) + .05);
					})()
					""").asDouble()).as("Brand text contrast at %s px", width).isGreaterThanOrEqualTo(4.5);
				assertThat(chrome.evaluate("""
					(() => {
					  const text = document.createRange();
					  text.selectNodeContents(document.querySelector('.prime-root-tagline'));
					  return Math.abs(text.getBoundingClientRect().left -
					    document.querySelector('#username').getBoundingClientRect().left);
					})()
					""").asDouble()).as("Tagline alignment at %s px", width).isLessThanOrEqualTo(2);
			}
			assertThat(chrome.evaluate("document.querySelector('.prime-root-illustration img').alt").asText())
				.contains("red bulb onions, tomatoes and capsicum");
			chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
				"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
			assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "KEYCLOAK_THEME_TEST_URL", matches = ".+")
	void keycloakThemeRetainsNativeCredentialsErrorsRecoveryAndAuthenticatorSetup() throws Exception {
		var url = System.getenv("KEYCLOAK_THEME_TEST_URL");
		var uri = URI.create(url);
		assertThat(uri.getHost()).isIn("localhost", "127.0.0.1");
		assertThat(uri.getPath()).isEqualTo("/realms/prime-root-theme-test/protocol/openid-connect/auth");
		try (var chrome = new Chrome()) {
			chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
				"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
			chrome.command("Page.navigate", Map.of("url", url));
			chrome.await("document.querySelector('#kc-form-login') !== null && document.readyState === 'complete'");
			assertThat(chrome.evaluate("document.title").asText()).contains("Prime Root");
			assertThat(chrome.evaluate("document.querySelector('#kc-header-wrapper').textContent").asText()).isEqualTo("Prime Root");
			assertThat(chrome.evaluate("document.querySelector('.prime-root-story').innerText").asText())
				.contains("Fresh produce.", "Great connections.", "YOUR FRESH PRODUCE DISTRIBUTION NETWORK");
			assertThat(chrome.evaluate("document.querySelector('.prime-root-illustration img').alt").asText())
				.contains("onions, tomatoes and capsicum", "network connections");
			assertThat(chrome.evaluate("[...document.images].every(image => image.complete && image.naturalWidth > 0)").asBoolean()).isTrue();
			assertThat(chrome.evaluate("getComputedStyle(document.querySelector('#kc-login')).backgroundColor").asText())
				.isEqualTo("rgb(23, 103, 69)");
			assertThat(chrome.evaluate("""
				document.querySelector('.prime-root-story').getBoundingClientRect().right <
				document.querySelector('#kc-form-login').getBoundingClientRect().left
				""").asBoolean()).withFailMessage("Desktop theme layout: %s", chrome.evaluate("""
				JSON.stringify({width: innerWidth, story: document.querySelector('.prime-root-story').getBoundingClientRect(),
				form: document.querySelector('#kc-form-login').getBoundingClientRect(),
				mainDisplay: getComputedStyle(document.querySelector('main')).display,
				footerDisplay: getComputedStyle(document.querySelector('main > div:last-child')).display})
				""").asText()).isTrue();
			chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
				"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
			assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
			chrome.evaluate("""
				document.querySelector('#username').value = 'theme-fixture';
				document.querySelector('#password').value = 'deliberately-invalid-password';
				document.querySelector('#kc-form-login').requestSubmit(); true
				""");
			chrome.await("document.body !== null && document.body.innerText.includes('Invalid username or password')");
			assertThat(chrome.evaluate("document.querySelector('.prime-root-story') !== null").asBoolean()).isTrue();
			chrome.evaluate("document.querySelector('a[href*=\"reset-credentials\"]').click(); true");
			chrome.await("document.querySelector('#kc-reset-password-form') !== null && document.readyState === 'complete'");
			assertThat(chrome.evaluate("document.querySelector('.prime-root-story') !== null").asBoolean()).isTrue();
			assertThat(chrome.evaluate("document.querySelector('#username') !== null").asBoolean()).isTrue();
			chrome.command("Page.navigate", Map.of("url", url));
			chrome.await("document.querySelector('#kc-form-login') !== null && document.readyState === 'complete'");
			var password = System.getenv("KEYCLOAK_THEME_TEST_PASSWORD");
			assertThat(password).as("Password for the isolated theme-fixture test account").isNotBlank();
			chrome.evaluate("document.querySelector('#username').value = 'theme-fixture';"
				+ "document.querySelector('#password').value = " + json.writeValueAsString(password) + ";"
				+ "document.querySelector('#kc-form-login').requestSubmit(); true");
			chrome.await("document.querySelector('#totp') !== null && document.readyState === 'complete'");
			assertThat(chrome.evaluate("document.querySelector('.prime-root-story') !== null").asBoolean()).isTrue();
			assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
		}
	}

	private void verifyInventoryWorkspace(Chrome chrome, Browser admin, String branchId, String productId) throws Exception {
		var catalogue = admin.body(admin.get("/api/catalogue"));
		var type = catalogue.get("products").get(0).get("produceTypeId").asText();
		var small = admin.body(admin.mutate("POST", "/api/catalogue/classifications", Map.of("code", "UI_SMALL", "name", "Small",
			"active", true, "produceTypeId", type, "kind", "GRADE"))).get("id").asText();
		var medium = admin.body(admin.mutate("POST", "/api/catalogue/classifications", Map.of("code", "UI_MEDIUM", "name", "Medium",
			"active", true, "produceTypeId", type, "kind", "GRADE"))).get("id").asText();
		var otherType = admin.body(admin.mutate("POST", "/api/catalogue/types", Map.of("code", "UI_OTHER", "name", "Other type", "active", true))).get("id").asText();
		admin.mutate("POST", "/api/catalogue/classifications", Map.of("code", "UI_OTHER_GRADE", "name", "Wrong-type grade",
			"active", true, "produceTypeId", otherType, "kind", "GRADE"));
		navigateSidebar(chrome, "stock", "/ui/stock-entries");
		chrome.await("document.querySelector('#stock-receipt') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("getComputedStyle(document.querySelector('.stock-entry-layout')).gridTemplateColumns.split(' ').length").asInt()).isEqualTo(2);
		chrome.evaluate("""
			(() => { const form = document.querySelector('#stock-receipt');
			form.elements.source.value = 'UI shared delivery';
			const card = document.querySelector('[data-add-product][data-id="%s"]');
			card.click(); card.click(); return true; })()
			""".formatted(productId));
		assertThat(chrome.evaluate("document.querySelectorAll('#receipt-lines .receipt-line').length").asInt()).isEqualTo(2);
		assertThat(chrome.evaluate("""
			[...document.querySelectorAll('#receipt-lines [name="gradeId"]')].every(field => field.value === '')
			&& [...document.querySelectorAll('#receipt-lines [name="lotCode"]')].every(field => field.value === 'UI shared delivery')
			""").asBoolean()).isTrue();
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
		assertThat(chrome.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean()).isTrue();
		chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
			"width", 1440, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
		chrome.evaluate("document.querySelectorAll('[data-remove-line]')[1].click(); true");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Receive stock");
		assertThat(chrome.evaluate("document.querySelector('#stock-history') === null").asBoolean()).isTrue();
		chrome.evaluate("""
			(() => { const line = document.querySelector('.receipt-line');
			const product = line.querySelector('[name="productId"]');
			product.value = '%s'; product.dispatchEvent(new Event('change', {bubbles: true}));
			line.querySelector('[name="gradeId"]').value = '%s'; return true; })()
			""".formatted(productId, small));
		assertThat(chrome.evaluate("""
			[...document.querySelector('.receipt-line [name="gradeId"]').options]
			.filter(option => !option.disabled).map(option => option.text).join(',')
			""").asText()).isEqualTo("Ungraded,Medium,Small");
		chrome.evaluate("""
			(() => { const product = document.querySelector('.receipt-line [name="productId"]');
			product.value = ''; product.dispatchEvent(new Event('change', {bubbles: true})); return true; })()
			""");
		assertThat(chrome.evaluate("document.querySelector('.receipt-line [name=\"gradeId\"]').value").asText()).isEmpty();
		chrome.evaluate("""
			(() => { const f = document.querySelector('#stock-receipt');
			Object.defineProperty(globalThis.crypto, 'getRandomValues', {value: undefined, configurable: true});
			f.elements.branchId.value = '%s'; f.elements.source.value = 'UI Farm';
			const line = f.querySelector('.receipt-line');
			line.querySelector('[name="productId"]').value = '%s';
			line.querySelector('[name="productId"]').dispatchEvent(new Event('change', {bubbles: true}));
			line.querySelector('[name="gradeId"]').value = '%s';
			line.querySelector('[name="unitId"]').value = '10000000-0000-0000-0000-000000000001';
			line.querySelector('[name="quantity"]').value = '2';
			line.querySelector('[name="purchaseUnitCost"]').value = '80.123456';
			line.querySelector('[name="lotCode"]').value = 'UI_DELIVERY_LOT';
			document.querySelector('#add-receipt-line').click();
			const measured = f.querySelectorAll('.receipt-line')[1];
			measured.querySelector('[name="productId"]').value = '%s';
			measured.querySelector('[name="productId"]').dispatchEvent(new Event('change', {bubbles: true}));
			measured.querySelector('[name="gradeId"]').value = '%s';
			measured.querySelector('[name="unitId"]').value = '10000000-0000-0000-0000-000000000005';
			measured.querySelector('[name="quantity"]').value = '2';
			measured.querySelector('[name="purchaseUnitCost"]').value = '2000';
			measured.querySelector('[name="measuredBaseQuantity"]').value = '3.123456';
			measured.querySelector('[name="lotCode"]').value = 'UI_DELIVERY_LOT';
			return true; })()
			""".formatted(branchId, productId, small, productId, medium));
		var stockScreenshot = System.getProperty("ui.stock-screenshot");
		if (stockScreenshot != null) {
			var capture = chrome.command("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true));
			Files.write(Path.of(stockScreenshot), java.util.Base64.getDecoder().decode(capture.get("data").asText()));
		}
		chrome.evaluate("document.querySelector('#stock-receipt').requestSubmit(); true");
		chrome.await("document.querySelector('#stock-receipt .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#stock-receipt .form-message').textContent").asText())
			.contains("Secure request identifiers are unavailable");
		chrome.evaluate("delete globalThis.crypto.getRandomValues; document.querySelector('#stock-receipt').requestSubmit(); true");
		chrome.await("document.querySelector('#stock-batches')?.innerText.includes('UI_DELIVERY_LOT') === true && document.readyState === 'complete'");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#stock-balances').innerText").asText()).contains("5.123456 KG");
		assertThat(chrome.evaluate("document.querySelector('#stock-batches').innerText").asText())
			.contains("KES 160.25", "KES 4000.00", "2000.000000 / BAG", "Small", "Medium");
		navigateSidebar(chrome, "stock", "/ui/stock");
		chrome.await("document.querySelector('form[data-stock=\"spoilage\"]') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Stock Movement");
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="spoilage"]');
			const batch = [...f.elements.batchId.options].find(o => o.text.includes(' / Medium / UI_DELIVERY_LOT'));
			f.elements.batchId.value = batch.value; f.elements.batchId.dispatchEvent(new Event('change'));
			f.elements.quantity.value = '0.123456'; f.elements.reason.value = 'UI bruising';
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances')?.innerText.includes('5.000000 KG') === true");
		chrome.ready();
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="adjustments"]');
			const batch = [...f.elements.batchId.options].find(o => o.text.includes(' / Small / UI_DELIVERY_LOT'));
			f.elements.batchId.value = batch.value; f.elements.batchId.dispatchEvent(new Event('change'));
			f.elements.quantity.value = '1'; f.elements.reason.value = 'UI count correction';
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances')?.innerText.includes('6.000000 KG') === true");
		chrome.ready();
		chrome.evaluate("""
			(() => { const row = [...document.querySelectorAll('#stock-history tbody tr')]
			.find(r => r.innerText.includes('UI count correction')); row.querySelector('[data-reverse]').click();
			const f = document.querySelector('form[data-stock="reversals"]');
			f.elements.reason.value = 'UI reversal'; f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances')?.innerText.includes('5.000000 KG') === true");
		chrome.ready();
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="thresholds"]');
			f.elements.branchId.value = '%s'; f.elements.productId.value = '%s';
			f.elements.minimumQuantity.value = '7'; f.requestSubmit(); return true; })()
			""".formatted(branchId, productId));
		chrome.await("document.querySelector('#stock-balances').innerText.includes('At or below minimum')");
		chrome.ready();
		chrome.evaluate("""
			(() => { document.querySelector('[data-threshold]').click();
			const f = document.querySelector('form[data-stock="thresholds"]');
			f.elements.minimumQuantity.value = '0'; f.requestSubmit(); return true; })()
			""");
		chrome.await("!document.querySelector('#stock-balances').innerText.includes('At or below minimum')");
		chrome.ready();
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="spoilage"]');
			f.elements.batchId.selectedIndex = 1; f.elements.batchId.dispatchEvent(new Event('change'));
			f.elements.quantity.value = '99'; f.elements.reason.value = 'Too much';
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('form[data-stock=\"spoilage\"] .form-message').classList.contains('error')");
		assertThat(chrome.evaluate("document.querySelector('#stock-balances').innerText").asText()).contains("5.000000 KG");
		navigateSidebar(chrome, "reports", "/ui/reports/stock");
		assertThat(chrome.evaluate("document.querySelector('#stock-batches').innerText").asText()).contains("KES 160.25", "KES 4000.00");
		assertThat(chrome.evaluate("document.querySelector('#stock-balances').innerText").asText()).contains("5.000000 KG");
		assertThat(chrome.evaluate("document.querySelectorAll('form[data-stock], [data-reverse], [data-threshold]').length").asInt()).isZero();
		navigateSidebar(chrome, "reports", "/ui/reports/stock-movements");
		assertThat(chrome.evaluate("document.querySelector('#stock-history').innerText").asText()).contains("UI bruising", "UI count correction");
		assertThat(chrome.evaluate("document.querySelectorAll('form[data-stock], [data-reverse], [data-threshold]').length").asInt()).isZero();
		navigateSidebar(chrome, "reports", "/ui/reports/audit");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Audit activity report");
		navigateSidebar(chrome, "configurations", "/ui/branches");
		navigateSidebar(chrome, "configurations", "/ui/catalogue");
	}

	private void navigateSidebar(Chrome chrome, String group, String path) throws Exception {
		chrome.evaluate("""
			(() => { const group = document.querySelector('#nav-%s');
			if (!group.open) group.querySelector('summary').click(); return true; })()
			""".formatted(group));
		chrome.await("document.querySelector('#nav-%s a[href=\"%s\"]').getClientRects().length > 0".formatted(group, path));
		chrome.evaluate("document.querySelector('#nav-%s a[href=\"%s\"]').click(); true".formatted(group, path));
		chrome.await("""
			location.pathname === '%s' && document.readyState === 'complete' &&
			document.querySelector('#nav-%s').open &&
			document.querySelector('#nav-%s a[aria-current="page"]').getAttribute('href') === '%s'
			""".formatted(path, group, group, path));
		assertThat(chrome.evaluate("document.querySelectorAll('.sidebar a[aria-current=\"page\"]').length").asInt()).isEqualTo(1);
	}

	private final class Chrome implements WebSocket.Listener, AutoCloseable {
		private final HttpClient client = HttpClient.newHttpClient();
		private final AtomicInteger ids = new AtomicInteger();
		private final Map<Integer, CompletableFuture<JsonNode>> replies = new ConcurrentHashMap<>();
		private final StringBuilder buffer = new StringBuilder();
		private Process process;
		private WebSocket socket;

		Chrome() throws Exception {
			try {
				Files.createDirectories(profile);
				process = new ProcessBuilder(System.getProperty("ui.browser"), "--headless", "--disable-gpu",
					"--no-first-run", "--disable-background-networking", "--disable-extensions",
					"--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank")
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(profile.resolve("browser.log").toFile()).start();
				var activePort = profile.resolve("DevToolsActivePort");
				for (int attempt = 0; attempt < 200 && !Files.exists(activePort); attempt++) {
					TimeUnit.MILLISECONDS.sleep(100);
				}
				int debugPort = Integer.parseInt(Files.readAllLines(activePort).getFirst());
				var request = HttpRequest.newBuilder(URI.create("http://localhost:" + debugPort + "/json/new?about:blank"))
					.timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.noBody()).build();
				var tab = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
				socket = client.newWebSocketBuilder().buildAsync(
					URI.create(tab.get("webSocketDebuggerUrl").asText()), this).get(10, TimeUnit.SECONDS);
				command("Page.enable", Map.of());
				command("Runtime.enable", Map.of());
			}
			catch (Exception exception) {
				close();
				throw exception;
			}
		}

		void ready() throws Exception { await("document.readyState === 'complete'"); }

		void await(String expression) throws Exception {
			for (int attempt = 0; attempt < 150; attempt++) {
				if (evaluate(expression).asBoolean()) {
					return;
				}
				TimeUnit.MILLISECONDS.sleep(100);
			}
			throw new AssertionError("Browser condition timed out: " + expression);
		}

		JsonNode evaluate(String expression) throws Exception {
			var result = command("Runtime.evaluate", Map.of("expression", expression, "returnByValue", true));
			assertThat(result.has("exceptionDetails")).withFailMessage(result.toString()).isFalse();
			return result.get("result").get("value");
		}

		JsonNode command(String method, Map<String, ?> parameters) throws Exception {
			int id = ids.incrementAndGet();
			var future = new CompletableFuture<JsonNode>();
			replies.put(id, future);
			socket.sendText(json.writeValueAsString(Map.of("id", id, "method", method, "params", parameters)), true).join();
			var response = future.get(15, TimeUnit.SECONDS);
			assertThat(response.has("error")).withFailMessage(response.toString()).isFalse();
			return response.get("result");
		}

		@Override
		public void onOpen(WebSocket webSocket) { webSocket.request(1); }

		@Override
		public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				var message = json.readTree(buffer.toString());
				buffer.setLength(0);
				if (message.has("id")) {
					var future = replies.remove(message.get("id").asInt());
					if (future != null) {
						future.complete(message);
					}
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			replies.values().forEach(reply -> reply.completeExceptionally(error));
		}

		@Override
		public void close() throws InterruptedException {
			if (socket != null) {
				socket.abort();
			}
			client.shutdownNow();
			if (process != null) {
				process.descendants().forEach(ProcessHandle::destroy);
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
		}
	}
}
