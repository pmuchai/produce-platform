package com.primeroot.platform;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.primeroot.platform.access.Permission;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"platform.bootstrap.enabled=false", "server.servlet.session.cookie.secure=false"})
abstract class OidcTestSupport {
	static final OidcTestProvider PROVIDER = provider();
	static final List<String> ALL_PERMISSIONS = Arrays.stream(Permission.values()).map(Enum::name).toList();
	final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@DynamicPropertySource
	static void identityProvider(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", PROVIDER::issuer);
		registry.add("spring.security.oauth2.client.registration.keycloak.client-id", () -> OidcTestProvider.CLIENT_ID);
		registry.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> OidcTestProvider.CLIENT_SECRET);
	}

	Browser browser() {
		return new Browser();
	}

	final class Browser implements AutoCloseable {
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
			.connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

		HttpResponse<String> login(String subject, Object roles, OidcTestProvider.Fault fault) throws Exception {
			PROVIDER.identity(subject, roles, fault);
			var start = get("/oauth2/authorization/keycloak");
			assertThat(start.statusCode()).isEqualTo(302);
			var authorization = send("GET", URI.create(start.headers().firstValue("Location").orElseThrow()), null, null);
			assertThat(authorization.statusCode()).isEqualTo(302);
			var callback = send("GET", URI.create(authorization.headers().firstValue("Location").orElseThrow()), null, null);
			if (callback.statusCode() != 302) {
				return callback;
			}
			assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/home");
			var home = get(callback.headers().firstValue("Location").orElseThrow());
			assertThat(home.statusCode()).withFailMessage(home.body()).isEqualTo(200);
			assertThat(home.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
			return get("/api/auth/me");
		}

		HttpResponse<String> adminLogin() throws Exception {
			var result = login(OidcTestProvider.ADMIN_SUBJECT, ALL_PERMISSIONS, OidcTestProvider.Fault.NONE);
			assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
			return result;
		}

		HttpResponse<String> get(String path) throws Exception {
			return send("GET", URI.create("http://localhost:" + port).resolve(path), null, null);
		}

		HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
			var csrfResponse = get("/api/auth/csrf");
			assertThat(csrfResponse.statusCode()).isEqualTo(200);
			var csrf = json.readTree(csrfResponse.body());
			return send(method, URI.create("http://localhost:" + port + path), body,
				Map.entry(csrf.get("headerName").asText(), csrf.get("token").asText()));
		}

		HttpResponse<String> mutateWithIfMatch(String method, String path, Object body, String ifMatch)
				throws Exception {
			var csrfResponse = get("/api/auth/csrf");
			assertThat(csrfResponse.statusCode()).isEqualTo(200);
			var csrf = json.readTree(csrfResponse.body());
			return send(method, URI.create("http://localhost:" + port + path), body,
				Map.entry(csrf.get("headerName").asText(), csrf.get("token").asText()), ifMatch);
		}

		HttpResponse<String> send(String method, URI uri, Object body, Map.Entry<String, String> csrf) throws Exception {
			return send(method, uri, body, csrf, null);
		}

		HttpResponse<String> send(String method, URI uri, Object body, Map.Entry<String, String> csrf,
				String ifMatch) throws Exception {
			var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
				.header("Accept", "/home".equals(uri.getPath()) ? "text/html" : "application/json");
			if (csrf != null) {
				request.header(csrf.getKey(), csrf.getValue());
			}
			if (ifMatch != null) {
				request.header("If-Match", ifMatch);
			}
			if (body != null) {
				request.header("Content-Type", "application/json");
			}
			request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}

		JsonNode body(HttpResponse<String> response) {
			return json.readTree(response.body());
		}

		@Override
		public void close() {
			client.close();
		}
	}

	private static OidcTestProvider provider() {
		try {
			return new OidcTestProvider();
		}
		catch (java.io.IOException | com.nimbusds.jose.JOSEException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}
}
