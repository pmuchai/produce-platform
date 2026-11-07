package com.primeroot.platform.whatsapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppWebhookController {
	private final WhatsAppProperties properties;
	private final WhatsAppCrypto crypto;
	private final WhatsAppStore store;
	private final JsonMapper json;

	public WhatsAppWebhookController(WhatsAppProperties properties, WhatsAppCrypto crypto,
			WhatsAppStore store, JsonMapper json) {
		this.properties = properties;
		this.crypto = crypto;
		this.store = store;
		this.json = json;
	}

	@GetMapping(value = "/api/whatsapp/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", defaultValue = "") String token,
			@RequestParam(name = "hub.challenge", defaultValue = "") String challenge) {
		if (!"subscribe".equals(mode) || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
				properties.verifyToken().getBytes(StandardCharsets.UTF_8))
				|| !challenge.matches("[0-9]{1,128}")) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook verification failed");
		}
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(challenge);
	}

	@PostMapping("/api/whatsapp/webhook")
	public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
		var bytes = request.getInputStream().readNBytes(131_073);
		if (bytes.length > 131_072) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Webhook body too large");
		if (!crypto.validSignature(bytes, request.getHeader("X-Hub-Signature-256"))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook signature invalid");
		}
		var messages = new ArrayList<WhatsAppStore.Message>();
		try {
			var payload = json.readTree(bytes);
			if (!"whatsapp_business_account".equals(payload.path("object").asText())) return ResponseEntity.ok().build();
			for (var entry : payload.path("entry")) {
				for (var change : entry.path("changes")) {
					var value = change.path("value");
					if (!"messages".equals(change.path("field").asText())
							|| !"whatsapp".equals(value.path("messaging_product").asText())
							|| !properties.phoneNumberId().equals(value.path("metadata").path("phone_number_id").asText())) continue;
					for (var message : value.path("messages")) {
						if (messages.size() >= 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many messages");
						var id = message.path("id").asText("");
						var sender = message.path("from").asText("");
						if (id.isBlank() || id.length() > 255 || !sender.matches("[1-9][0-9]{5,14}")) continue;
						Instant sentAt;
						try { sentAt = Instant.ofEpochSecond(Long.parseLong(message.path("timestamp").asText(""))); }
						catch (RuntimeException exception) { continue; }
						var now = Instant.now();
						if (!sentAt.isAfter(now.minusSeconds(86_400)) || sentAt.isAfter(now.plusSeconds(60))) continue;
						var text = "text".equals(message.path("type").asText()) ? message.path("text").path("body").asText("") : "";
						String command = command(text);
						String hash = "LINK".equals(command) ? WhatsAppCrypto.hash(text.trim().substring(5)) : null;
						messages.add(new WhatsAppStore.Message(id, sender, command, hash, sentAt, 0, null, null));
					}
				}
			}
		}
		catch (tools.jackson.core.JacksonException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed webhook JSON");
		}
		store.enqueue(messages);
		return ResponseEntity.ok().build();
	}

	private static String command(String text) {
		if (text.length() > 512) return "HELP";
		var trimmed = text.trim();
		if (trimmed.matches("(?i)LINK [A-Za-z0-9_-]{43}")) return "LINK";
		var normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[?!.]+$", "").replaceAll("\\s+", " ");
		return switch (normalized) {
			case "sales today", "today's sales", "total amount sold today",
				"what is the total amount sold today", "what's the total amount sold today",
				"how much did we sell today" -> "TODAY";
			case "sales yesterday" -> "YESTERDAY";
			case "sales this week" -> "WEEK";
			default -> "HELP";
		};
	}
}
