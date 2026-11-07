package com.primeroot.platform.security;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

public class ConditionalAuthorizedClientService implements OAuth2AuthorizedClientService {
	private record Key(String registrationId, String principalName) {}

	private final Map<Key, OAuth2AuthorizedClient> clients = new HashMap<>();

	@Override
	@SuppressWarnings("unchecked")
	public synchronized <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
			String principalName) {
		return (T) clients.get(new Key(clientRegistrationId, principalName));
	}

	@Override
	public synchronized void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
		clients.put(new Key(authorizedClient.getClientRegistration().getRegistrationId(), principal.getName()),
			authorizedClient);
	}

	@Override
	public synchronized void removeAuthorizedClient(String clientRegistrationId, String principalName) {
		clients.remove(new Key(clientRegistrationId, principalName));
	}

	public synchronized boolean saveRefreshTokenIfCurrent(String clientRegistrationId, Authentication principal,
			String expectedRefreshToken, String rotatedRefreshToken) {
		var current = loadAuthorizedClient(clientRegistrationId, principal.getName());
		if (current == null || current.getRefreshToken() == null
				|| !expectedRefreshToken.equals(current.getRefreshToken().getTokenValue())) return false;
		saveAuthorizedClient(new OAuth2AuthorizedClient(current.getClientRegistration(), principal.getName(),
			current.getAccessToken(), new OAuth2RefreshToken(rotatedRefreshToken, java.time.Instant.now())), principal);
		return true;
	}
}
