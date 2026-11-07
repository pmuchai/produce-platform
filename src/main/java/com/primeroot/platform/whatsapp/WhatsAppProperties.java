package com.primeroot.platform.whatsapp;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.whatsapp")
public record WhatsAppProperties(String appSecret, String verifyToken, String accessToken,
		String phoneNumberId, String businessNumber, String apiVersion, String encryptionKey,
		@DefaultValue("true") boolean workerEnabled) {
	public WhatsAppProperties {
		if (appSecret == null || !appSecret.matches("[a-fA-F0-9]{32}")
				|| verifyToken == null || verifyToken.length() < 32
				|| accessToken == null || accessToken.isBlank()
				|| phoneNumberId == null || !phoneNumberId.matches("[0-9]{6,32}")
				|| businessNumber == null || !businessNumber.matches("[1-9][0-9]{5,14}")
				|| apiVersion == null || !apiVersion.matches("v[0-9]{1,3}\\.[0-9]{1,2}")) {
			throw new IllegalArgumentException("Configure all WhatsApp credentials, numbers and Graph API version");
		}
		try {
			if (Base64.getDecoder().decode(encryptionKey).length != 32) throw new IllegalArgumentException();
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("WhatsApp encryption-key must be a base64-encoded 32-byte key");
		}
	}

	@Override
	public String toString() {
		return "WhatsAppProperties[credentials redacted]";
	}
}
