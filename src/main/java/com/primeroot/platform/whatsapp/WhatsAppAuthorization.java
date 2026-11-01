package com.primeroot.platform.whatsapp;

import java.util.UUID;
import java.util.function.Consumer;

import com.primeroot.platform.access.StaffUserRepository;
import com.primeroot.platform.security.StaffPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppAuthorization {
	private final WhatsAppStore store;
	private final StaffUserRepository staff;
	private final WhatsAppCrypto crypto;
	private final WhatsAppOidcClient oidc;

	public WhatsAppAuthorization(WhatsAppStore store, StaffUserRepository staff,
			WhatsAppCrypto crypto, WhatsAppOidcClient oidc) {
		this.store = store;
		this.staff = staff;
		this.crypto = crypto;
		this.oidc = oidc;
	}

	@Transactional(noRollbackFor = AccessDeniedException.class)
	public StaffPrincipal authenticate(String sender, UUID linkId) {
		var found = store.sender(sender).filter(link -> link.id().equals(linkId)).orElseThrow(WhatsAppOidcClient::denied);
		store.lockStaff(found.staffId());
		var link = store.link(found.staffId(), true).filter(value -> value.sender().equals(sender) && value.id().equals(linkId))
			.orElseThrow(WhatsAppOidcClient::denied);
		var user = staff.findById(link.staffId()).filter(value -> value.isActive()
			&& value.getSecurityVersion() == link.securityVersion()).orElseThrow(WhatsAppOidcClient::denied);
		if (link.reauthenticationRequired()) throw WhatsAppOidcClient.denied();
		try {
			var grant = oidc.refresh(user, crypto.decrypt(user.getId(), link.refreshToken()), link.nonceHash());
			store.updateGrant(user.getId(), crypto.encrypt(user.getId(), grant.refreshToken()));
			return grant.principal();
		}
		catch (AccessDeniedException exception) {
			store.requireReauthentication(user.getId());
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public boolean stillLinked(String sender, UUID linkId, StaffPrincipal principal) {
		return java.time.Instant.now().isBefore(principal.getIdToken().getExpiresAt())
			&& store.link(principal.getStaffId(), false).filter(link -> link.sender().equals(sender)
			&& link.id().equals(linkId) && !link.reauthenticationRequired()
			&& link.securityVersion() == principal.getSecurityVersion()).isPresent()
			&& staff.findById(principal.getStaffId()).filter(user -> user.isActive()
				&& user.getSecurityVersion() == principal.getSecurityVersion()).isPresent();
	}

	@Transactional
	public void dispatchIfStillLinked(String sender, UUID linkId, StaffPrincipal principal, String reply,
			Consumer<String> dispatch) {
		store.lockStaff(principal.getStaffId());
		dispatch.accept(stillLinked(sender, linkId, principal) ? reply
			: "WhatsApp access changed. Sign in to Prime Root and renew linking.");
	}
}
