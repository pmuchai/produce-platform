package com.primeroot.platform.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppCrypto {
	private final WhatsAppProperties properties;
	private final SecureRandom random = new SecureRandom();

	public WhatsAppCrypto(WhatsAppProperties properties) {
		this.properties = properties;
	}

	public String challenge() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String hash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	public boolean validSignature(byte[] body, String signature) {
		if (signature == null || !signature.matches("sha256=[a-fA-F0-9]{64}")) return false;
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7)));
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is required", exception);
		}
	}

	public String encrypt(UUID staffId, String token) {
		try {
			var iv = new byte[12];
			random.nextBytes(iv);
			var cipher = cipher(Cipher.ENCRYPT_MODE, staffId, iv);
			var encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
			var result = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, result, 0, iv.length);
			System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
			return Base64.getEncoder().encodeToString(result);
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Could not protect WhatsApp authorization");
		}
	}

	public String decrypt(UUID staffId, String encrypted) {
		try {
			var bytes = Base64.getDecoder().decode(encrypted);
			if (bytes.length < 28) throw new IllegalArgumentException();
			var iv = java.util.Arrays.copyOf(bytes, 12);
			return new String(cipher(Cipher.DECRYPT_MODE, staffId, iv)
				.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
		}
		catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
			throw new org.springframework.security.access.AccessDeniedException("Sign in and renew WhatsApp access");
		}
	}

	private Cipher cipher(int mode, UUID staffId, byte[] iv) throws java.security.GeneralSecurityException {
		var cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()), "AES"),
			new GCMParameterSpec(128, iv));
		cipher.updateAAD(staffId.toString().getBytes(StandardCharsets.US_ASCII));
		return cipher;
	}
}
