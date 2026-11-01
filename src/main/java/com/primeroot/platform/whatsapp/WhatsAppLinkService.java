package com.primeroot.platform.whatsapp;

import java.time.Instant;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffUserRepository;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.security.StaffPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppLinkService {
	private final AccessService access;
	private final StaffUserRepository staff;
	private final WhatsAppStore store;
	private final WhatsAppCrypto crypto;
	private final WhatsAppProperties properties;
	private final WhatsAppOidcClient oidc;
	private final AuditRepository audit;

	public WhatsAppLinkService(AccessService access, StaffUserRepository staff, WhatsAppStore store,
			WhatsAppCrypto crypto, WhatsAppProperties properties, WhatsAppOidcClient oidc, AuditRepository audit) {
		this.access = access;
		this.staff = staff;
		this.store = store;
		this.crypto = crypto;
		this.properties = properties;
		this.oidc = oidc;
		this.audit = audit;
	}

	public record Status(String sender, boolean reauthenticationRequired, WhatsAppStore.Challenge challenge) {}
	public record Created(UUID id, String code, Instant expiresAt, String whatsappUrl) {}

	@Transactional(readOnly = true)
	public Status status() {
		var user = access.currentUser();
		var link = store.link(user.getId(), false);
		var challenge = store.challenge(user.getId()).filter(value -> value.expiresAt().isAfter(Instant.now()));
		return new Status(link.map(WhatsAppStore.Link::sender).orElse(null), link.map(value ->
			value.reauthenticationRequired() || value.securityVersion() != user.getSecurityVersion()).orElse(false),
			challenge.orElse(null));
	}

	@Transactional
	public Created create() {
		var user = salesUser();
		store.lockStaff(user.getId());
		var code = crypto.challenge();
		var id = UUID.randomUUID();
		var expires = Instant.now().plusSeconds(600);
		store.newChallenge(user.getId(), id, WhatsAppCrypto.hash(code), user.getSecurityVersion(), expires);
		audit.save(new AuditEvent(user.getId(), "WHATSAPP_LINK_REQUESTED", "USER", user.getId()));
		return new Created(id, code, expires, "https://wa.me/" + properties.businessNumber() + "?text=LINK%20" + code);
	}

	@Transactional
	public String verify(String hash, String sender) {
		var pending = store.challengeByHash(hash);
		if (pending.isEmpty()) return "Link code is invalid or expired. Request a new code in Prime Root.";
		var challenge = pending.get();
		store.lockStaff(challenge.staffId());
		var user = staff.findById(challenge.staffId()).orElseThrow(WhatsAppOidcClient::denied);
		if (!user.isActive() || user.getSecurityVersion() != challenge.securityVersion()
				|| !challenge.expiresAt().isAfter(Instant.now())) {
			return "Link code is invalid or expired. Request a new code in Prime Root.";
		}
		if (!store.verify(challenge.id(), sender)) {
			return "Link code has already been used. Return to Prime Root to check or request a new code.";
		}
		audit.save(new AuditEvent(user.getId(), "WHATSAPP_SENDER_VERIFIED", "USER", user.getId()));
		return "WhatsApp ownership verified. Return to Prime Root, check the number and confirm linking. No reports are enabled yet.";
	}

	@Transactional
	public Status confirm(UUID challengeId, OAuth2AuthorizedClient client) {
		var user = salesUser();
		store.lockStaff(user.getId());
		var challenge = store.challenge(user.getId()).filter(value -> value.id().equals(challengeId)
			&& value.securityVersion() == user.getSecurityVersion() && value.expiresAt().isAfter(Instant.now())
			&& value.verifiedSender() != null)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Verify the current code from WhatsApp before confirming"));
		if (store.sender(challenge.verifiedSender()).filter(value -> !value.staffId().equals(user.getId())).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This WhatsApp account is already linked; unlink it first");
		}
		if (client == null || client.getRefreshToken() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keycloak must issue a refresh token; sign in again");
		}
		var principal = (StaffPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		var nonce = principal.getIdToken().getClaimAsString("nonce");
		var nonceHash = nonce == null ? null : WhatsAppCrypto.hash(nonce);
		var grant = oidc.refresh(user, client.getRefreshToken().getTokenValue(), nonceHash);
		if (grant.principal().getAuthorities().stream().noneMatch(authority ->
				authority.getAuthority().equals(Permission.SALES_VIEW.name())
					|| authority.getAuthority().equals(Permission.SALE_CREATE.name()))) throw WhatsAppOidcClient.denied();
		store.saveLink(new WhatsAppStore.Link(user.getId(), UUID.randomUUID(), challenge.verifiedSender(), user.getSecurityVersion(),
			crypto.encrypt(user.getId(), grant.refreshToken()), nonceHash, false));
		audit.save(new AuditEvent(user.getId(), "WHATSAPP_LINKED", "USER", user.getId()));
		return status();
	}

	@Transactional
	public Status unlink() {
		var user = access.currentUser();
		store.lockStaff(user.getId());
		store.deleteLink(user.getId());
		audit.save(new AuditEvent(user.getId(), "WHATSAPP_UNLINKED", "USER", user.getId()));
		return status();
	}

	private com.primeroot.platform.access.StaffUser salesUser() {
		var user = access.currentUser();
		var permissions = access.permissions();
		if (!permissions.contains(Permission.SALES_VIEW) && !permissions.contains(Permission.SALE_CREATE)) {
			throw new AccessDeniedException("Sales viewing or selling permission is required");
		}
		return user;
	}
}
