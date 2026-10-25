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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.browser", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false", "platform.inventory.currency=KES",
	"spring.datasource.url=jdbc:h2:mem:catalogue-browser-tests;MODE=PostgreSQL"})
class CatalogueBrowserTests extends OidcTestSupport {
	@Autowired
	private StaffService staff;

	@TempDir
	Path profile;

	@Test
	void catalogueAndInventoryFormsPostConsecutiveChangesWithExactDecimalsAndVisibleErrors() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		try (var session = browser()) {
			session.adminLogin();
			try (var chrome = new Chrome()) {
				verifyPublicLogin(chrome);
				chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/ui/catalogue"));
				chrome.await("document.title.startsWith('Catalogue') && document.readyState === 'complete'");
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
				chrome.await("document.querySelector('#products table').innerText.includes('Edited UI Product')");
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
				var branchResponse = session.mutate("POST", "/api/branches", Map.of(
					"code", "UI_STOCK", "name", "UI Stock Branch", "active", true));
				assertThat(branchResponse.statusCode()).isEqualTo(201);
				var branchId = session.body(branchResponse).get("id").asText();
				var productId = session.body(session.get("/api/catalogue")).get("products").get(0).get("id").asText();
				verifyInventoryWorkspace(chrome, branchId, productId);
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

	private void verifyPublicLogin(Chrome chrome) throws Exception {
		chrome.command("Page.navigate", Map.of("url", "http://localhost:" + port + "/"));
		chrome.await("location.pathname === '/home' && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('.brand').textContent").asText()).isEqualTo("Prime Root");
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

	private void verifyInventoryWorkspace(Chrome chrome, String branchId, String productId) throws Exception {
		navigateSidebar(chrome, "stock", "/ui/stock-entries");
		chrome.await("document.querySelector('#stock-receipt') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Receive stock");
		assertThat(chrome.evaluate("document.querySelector('#stock-history') === null").asBoolean()).isTrue();
		chrome.evaluate("""
			(() => { const f = document.querySelector('#stock-receipt');
			f.elements.branchId.value = '%s'; f.elements.source.value = 'UI Farm';
			const line = f.querySelector('.receipt-line');
			line.querySelector('[name="productId"]').value = '%s';
			line.querySelector('[name="unitId"]').value = '10000000-0000-0000-0000-000000000001';
			line.querySelector('[name="quantity"]').value = '2';
			line.querySelector('[name="purchaseUnitCost"]').value = '80.123456';
			line.querySelector('[name="lotCode"]').value = 'UI_KG_LOT';
			document.querySelector('#add-receipt-line').click();
			const measured = f.querySelectorAll('.receipt-line')[1];
			measured.querySelector('[name="productId"]').value = '%s';
			measured.querySelector('[name="unitId"]').value = '10000000-0000-0000-0000-000000000005';
			measured.querySelector('[name="quantity"]').value = '2';
			measured.querySelector('[name="purchaseUnitCost"]').value = '2000';
			measured.querySelector('[name="measuredBaseQuantity"]').value = '3.123456';
			measured.querySelector('[name="lotCode"]').value = 'UI_MEASURED_LOT';
			f.requestSubmit(); return true; })()
			""".formatted(branchId, productId, productId));
		chrome.await("document.querySelector('#stock-batches').innerText.includes('UI_MEASURED_LOT')");
		chrome.ready();
		assertThat(chrome.evaluate("document.querySelector('#stock-balances').innerText").asText()).contains("5.123456 KG");
		assertThat(chrome.evaluate("document.querySelector('#stock-batches').innerText").asText())
			.contains("KES 160.25", "KES 4000.00", "2000.000000 / BAG");
		navigateSidebar(chrome, "stock", "/ui/stock");
		chrome.await("document.querySelector('form[data-stock=\"spoilage\"]') !== null && document.readyState === 'complete'");
		assertThat(chrome.evaluate("document.querySelector('h1').textContent").asText()).isEqualTo("Stock Movement");
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="spoilage"]');
			const batch = [...f.elements.batchId.options].find(o => o.text.includes('UI_MEASURED_LOT'));
			f.elements.batchId.value = batch.value; f.elements.batchId.dispatchEvent(new Event('change'));
			f.elements.quantity.value = '0.123456'; f.elements.reason.value = 'UI bruising';
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances').innerText.includes('5.000000 KG')");
		chrome.ready();
		chrome.evaluate("""
			(() => { const f = document.querySelector('form[data-stock="adjustments"]');
			const batch = [...f.elements.batchId.options].find(o => o.text.includes('UI_KG_LOT'));
			f.elements.batchId.value = batch.value; f.elements.batchId.dispatchEvent(new Event('change'));
			f.elements.quantity.value = '1'; f.elements.reason.value = 'UI count correction';
			f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances').innerText.includes('6.000000 KG')");
		chrome.ready();
		chrome.evaluate("""
			(() => { const row = [...document.querySelectorAll('#stock-history tbody tr')]
			.find(r => r.innerText.includes('UI count correction')); row.querySelector('[data-reverse]').click();
			const f = document.querySelector('form[data-stock="reversals"]');
			f.elements.reason.value = 'UI reversal'; f.requestSubmit(); return true; })()
			""");
		chrome.await("document.querySelector('#stock-balances').innerText.includes('5.000000 KG')");
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
