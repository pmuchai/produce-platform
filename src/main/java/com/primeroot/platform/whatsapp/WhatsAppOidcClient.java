package com.primeroot.platform.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffUser;
import com.primeroot.platform.security.StaffPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppOidcClient {
	private final ClientRegistrationRepository registrations;
	private final JsonMapper json;
	private final JwtDecoder decoder;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER).build();

	public WhatsAppOidcClient(ClientRegistrationRepository registrations, JsonMapper json) {
		this.registrations = registrations;
		this.json = json;
		this.decoder = new OidcIdTokenDecoderFactory().createDecoder(registrations.findByRegistrationId("keycloak"));
	}

	public record Grant(StaffPrincipal principal, String refreshToken) {
		@Override public String toString() { return "Grant[credentials redacted]"; }
	}

	public Grant refresh(StaffUser staff, String refreshToken, String nonceHash) {
		var registration = registrations.findByRegistrationId("keycloak");
		if (!staff.getIssuer().equals(registration.getProviderDetails().getIssuerUri())) throw denied();
		String form = "grant_type=refresh_token&refresh_token=" + encode(refreshToken);
		var request = HttpRequest.newBuilder(URI.create(registration.getProviderDetails().getTokenUri()))
			.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded");
		if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())) {
			var credentials = encode(registration.getClientId()) + ":" + encode(registration.getClientSecret());
			request.header("Authorization", "Basic " + Base64.getEncoder()
				.encodeToString(credentials.getBytes(StandardCharsets.US_ASCII)));
		}
		else if (ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(registration.getClientAuthenticationMethod())) {
			form += "&client_id=" + encode(registration.getClientId()) + "&client_secret=" + encode(registration.getClientSecret());
		}
		else throw denied();
		try {
			var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
				HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 429 || response.statusCode() >= 500) {
				throw new TemporarilyUnavailable();
			}
			if (response.statusCode() != 200 || response.body().length() > 65_536) throw denied();
			var body = json.readTree(response.body());
			var token = body.path("id_token").asText("");
			if (token.isBlank()) throw denied();
			var jwt = decoder.decode(token);
			var roles = jwt.getClaims().get("app_roles");
			var nonce = jwt.getClaimAsString("nonce");
			if (!staff.getSubject().equals(jwt.getSubject()) || !staff.getIssuer().equals(jwt.getIssuer().toString())
					|| jwt.getExpiresAt() == null || !Instant.now().isBefore(jwt.getExpiresAt())
					|| !(roles instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))
					|| (nonce != null && !WhatsAppCrypto.hash(nonce).equals(nonceHash))) throw denied();
			var permissions = Arrays.stream(Permission.values()).filter(permission -> values.contains(permission.name()))
				.collect(Collectors.toUnmodifiableSet());
			var idToken = new OidcIdToken(token, jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
			var identity = new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken);
			var rotated = body.path("refresh_token").asText(refreshToken);
			if (rotated.isBlank() || rotated.length() > 12_000) throw denied();
			return new Grant(new StaffPrincipal(staff, identity, permissions), rotated);
		}
		catch (java.io.IOException exception) {
			throw new TemporarilyUnavailable();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TemporarilyUnavailable();
		}
		catch (org.springframework.security.oauth2.jwt.JwtException | tools.jackson.core.JacksonException exception) {
			throw denied();
		}
	}

	static AccessDeniedException denied() {
		return new AccessDeniedException("Sign in to Prime Root and renew WhatsApp access");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public static class TemporarilyUnavailable extends RuntimeException {
		public TemporarilyUnavailable() { super("Identity service temporarily unavailable"); }
	}
}
