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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.browser", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false",
	"spring.datasource.url=jdbc:h2:mem:catalogue-browser-tests;MODE=PostgreSQL"})
class CatalogueBrowserTests extends OidcTestSupport {
	@Autowired
	private StaffService staff;

	@TempDir
	Path profile;

	@Test
	void consecutiveCatalogueSavesRefreshTablesAndCalculatorUsesExactPackagingDecimals() throws Exception {
		staff.bootstrap("admin", "Browser Administrator", OidcTestProvider.ADMIN_SUBJECT);
		try (var session = browser()) {
			session.adminLogin();
			var cookie = session.cookies.getCookieStore().getCookies().stream()
				.filter(item -> item.getName().equals("JSESSIONID")).findFirst().orElseThrow();
			try (var chrome = new Chrome()) {
				chrome.command("Network.setCookie", Map.of("name", "JSESSIONID", "value", cookie.getValue(),
					"url", "http://localhost:" + port, "httpOnly", true, "sameSite", "Lax"));
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
				chrome.command("Emulation.setDeviceMetricsOverride", Map.of(
					"width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
				assertThat(chrome.evaluate("document.documentElement.scrollWidth <= window.innerWidth").asBoolean()).isTrue();
			}
		}
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
