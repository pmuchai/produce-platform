package com.primeroot.platform.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

public final class PostingFingerprint {
	private PostingFingerprint() {
	}

	public static String of(JsonMapper json, StockOperation.Kind kind, UUID actor, Object request) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest((kind + ":" + actor + ":" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
