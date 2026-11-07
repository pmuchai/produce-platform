package com.primeroot.platform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

final class OidcTestProvider implements AutoCloseable {
	static final String CLIENT_ID = "produce-platform-test";
	static final String CLIENT_SECRET = "test-only-client-secret";
	static final String ADMIN_SUBJECT = "approved-admin-subject";

	enum Fault { NONE, ISSUER, AUDIENCE, SIGNATURE, NONCE, EXPIRED, SHORT_LIVED, SUBJECT, REVOKED }

	record Identity(String subject, Object roles, Fault fault) {
	}

	private record Grant(Map<String, String> parameters, Identity identity) {
	}

	private final HttpServer server;
	private final com.nimbusds.jose.jwk.RSAKey key;
	private final com.nimbusds.jose.jwk.RSAKey wrongKey;
	private final JsonMapper json = JsonMapper.builder().build();
	private final Map<String, Grant> codes = new ConcurrentHashMap<>();
	private final Map<String, Grant> refreshTokens = new ConcurrentHashMap<>();
	private final Map<String, Identity> currentIdentities = new ConcurrentHashMap<>();
	private volatile Identity identity;

	OidcTestProvider() throws IOException, JOSEException {
		key = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
		wrongKey = new RSAKeyGenerator(2048).keyID("untrusted-key").generate();
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/realms/test/.well-known/openid-configuration", this::discovery);
		server.createContext("/realms/test/auth", this::authorize);
		server.createContext("/realms/test/token", this::token);
		server.createContext("/realms/test/certs", exchange ->
			reply(exchange, 200, Map.of("keys", List.of(key.toPublicJWK().toJSONObject()))));
		server.createContext("/realms/test/logout", this::logout);
		server.start();
	}

	String issuer() {
		return "http://localhost:" + server.getAddress().getPort() + "/realms/test";
	}

	@Override
	public void close() {
		server.stop(0);
	}

	void identity(String subject, Object roles, Fault fault) {
		identity = new Identity(subject, roles, fault);
		currentIdentities.put(subject, identity);
	}

	private void discovery(HttpExchange exchange) throws IOException {
		reply(exchange, 200, Map.of(
			"issuer", issuer(), "authorization_endpoint", issuer() + "/auth",
			"token_endpoint", issuer() + "/token", "jwks_uri", issuer() + "/certs",
			"end_session_endpoint", issuer() + "/logout",
			"response_types_supported", List.of("code"), "subject_types_supported", List.of("public"),
			"id_token_signing_alg_values_supported", List.of("RS256"),
			"token_endpoint_auth_methods_supported", List.of("client_secret_basic"),
			"code_challenge_methods_supported", List.of("S256")));
	}

	private void authorize(HttpExchange exchange) throws IOException {
		var parameters = parameters(exchange.getRequestURI());
		var redirect = URI.create(parameters.get("redirect_uri"));
		if (!CLIENT_ID.equals(parameters.get("client_id"))
				|| !"code".equals(parameters.get("response_type"))
				|| !"S256".equals(parameters.get("code_challenge_method"))
				|| parameters.getOrDefault("code_challenge", "").isBlank()
				|| parameters.getOrDefault("state", "").isBlank()
				|| parameters.getOrDefault("nonce", "").isBlank()
				|| !"localhost".equals(redirect.getHost())
				|| !"/login/oauth2/code/keycloak".equals(redirect.getPath())) {
			reply(exchange, 400, Map.of("error", "invalid_authorization_request"));
			return;
		}
		var code = UUID.randomUUID().toString();
		codes.put(code, new Grant(parameters, identity));
		redirect(exchange, redirect + "?code=" + encode(code) + "&state=" + encode(parameters.get("state")));
	}

	private void token(HttpExchange exchange) throws IOException {
		var basic = "Basic " + Base64.getEncoder()
			.encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
		if (!"POST".equals(exchange.getRequestMethod()) || !basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
			reply(exchange, 401, Map.of("error", "invalid_client"));
			return;
		}
		var parameters = decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		if ("refresh_token".equals(parameters.get("grant_type"))) {
			var original = refreshTokens.remove(parameters.get("refresh_token"));
			if (original == null) {
				reply(exchange, 400, Map.of("error", "invalid_grant"));
				return;
			}
			var current = currentIdentities.get(original.identity().subject());
			if (current.fault() == Fault.REVOKED) {
				reply(exchange, 400, Map.of("error", "invalid_grant"));
				return;
			}
			issue(exchange, new Grant(original.parameters(), current));
			return;
		}
		var grant = codes.remove(parameters.get("code"));
		if (grant == null || !"authorization_code".equals(parameters.get("grant_type"))
				|| !grant.parameters().get("redirect_uri").equals(parameters.get("redirect_uri"))
				|| !grant.parameters().get("code_challenge").equals(challenge(parameters.getOrDefault("code_verifier", "")))) {
			reply(exchange, 400, Map.of("error", "invalid_grant"));
			return;
		}
		issue(exchange, grant);
	}

	private void issue(HttpExchange exchange, Grant grant) throws IOException {
		var refresh = UUID.randomUUID().toString();
		refreshTokens.put(refresh, grant);
		reply(exchange, 200, Map.of("access_token", "test-only-opaque-access-token", "token_type", "Bearer",
			"expires_in", 300, "id_token", signedToken(grant), "refresh_token", refresh));
	}

	private String signedToken(Grant grant) throws IOException {
		var fault = grant.identity().fault();
		var now = Instant.now();
		var claims = new JWTClaimsSet.Builder()
			.issuer(fault == Fault.ISSUER ? issuer() + "/wrong" : issuer())
			.subject(fault == Fault.SUBJECT ? "different-subject" : grant.identity().subject())
			.audience(fault == Fault.AUDIENCE ? "different-client" : CLIENT_ID)
			.issueTime(Date.from(now.minusSeconds(5)))
			.expirationTime(Date.from(fault == Fault.EXPIRED ? now.minusSeconds(120)
				: now.plusSeconds(fault == Fault.SHORT_LIVED ? 3 : 300)))
			.claim("nonce", fault == Fault.NONCE ? "incorrect-nonce" : grant.parameters().get("nonce"))
			.claim("preferred_username", "not-the-local-staff-username");
		if (grant.identity().roles() != null) {
			claims.claim("app_roles", grant.identity().roles());
		}
		var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
		try {
			token.sign(new RSASSASigner(fault == Fault.SIGNATURE ? wrongKey : key));
		}
		catch (JOSEException exception) {
			throw new IOException("Could not sign the test ID token", exception);
		}
		return token.serialize();
	}

	private void logout(HttpExchange exchange) throws IOException {
		var parameters = parameters(exchange.getRequestURI());
		try {
			var token = SignedJWT.parse(parameters.get("id_token_hint"));
			if (!issuer().equals(token.getJWTClaimsSet().getIssuer())) {
				reply(exchange, 400, Map.of("error", "invalid_id_token_hint"));
				return;
			}
			var subject = token.getJWTClaimsSet().getSubject();
			refreshTokens.entrySet().removeIf(entry -> entry.getValue().identity().subject().equals(subject));
		}
		catch (java.text.ParseException exception) {
			throw new IOException("Invalid logout ID-token hint", exception);
		}
		var target = URI.create(parameters.get("post_logout_redirect_uri"));
		if (!"localhost".equals(target.getHost()) || !"/".equals(target.getPath())) {
			reply(exchange, 400, Map.of("error", "invalid_post_logout_redirect_uri"));
			return;
		}
		redirect(exchange, target.toString());
	}

	private void reply(HttpExchange exchange, int status, Object body) throws IOException {
		var bytes = json.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private void redirect(HttpExchange exchange, String location) throws IOException {
		exchange.getResponseHeaders().set("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	static Map<String, String> parameters(URI uri) {
		return decode(uri.getRawQuery());
	}

	private static Map<String, String> decode(String form) {
		var result = new HashMap<String, String>();
		if (form != null && !form.isBlank()) {
			for (var pair : form.split("&")) {
				var parts = pair.split("=", 2);
				result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
					URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	private static String challenge(String verifier) {
		try {
			return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the JDK", exception);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
