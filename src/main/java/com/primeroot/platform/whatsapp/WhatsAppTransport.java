package com.primeroot.platform.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppTransport {
	private final WhatsAppProperties properties;
	private final JsonMapper json;
	private final HttpClient client;

	@Autowired
	public WhatsAppTransport(WhatsAppProperties properties, JsonMapper json) {
		this(properties, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER).build());
	}

	WhatsAppTransport(WhatsAppProperties properties, JsonMapper json, HttpClient client) {
		this.properties = properties;
		this.json = json;
		this.client = client;
	}

	public void send(String sender, String text) {
		if (!sender.matches("[1-9][0-9]{5,14}")) throw new IllegalArgumentException("Invalid WhatsApp recipient");
		if (text.length() > 4096) text = "This summary is too long for WhatsApp. View the full sales report in Prime Root.";
		var body = json.writeValueAsString(Map.of("messaging_product", "whatsapp", "recipient_type", "individual",
			"to", sender, "type", "text", "text", Map.of("preview_url", false, "body", text)));
		var request = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/" + properties.apiVersion()
				+ "/" + properties.phoneNumberId() + "/messages"))
			.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + properties.accessToken())
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
		try {
			var response = client.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() == 429) throw new DeliveryFailure(true, false);
			if (response.statusCode() >= 500) throw new DeliveryFailure(false, true);
			if (response.statusCode() < 200 || response.statusCode() >= 300) throw new DeliveryFailure(false, false);
		}
		catch (java.io.IOException exception) {
			throw new DeliveryFailure(false, true);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new DeliveryFailure(false, true);
		}
	}

	public static class DeliveryFailure extends RuntimeException {
		final boolean retryable;
		final boolean uncertain;
		public DeliveryFailure(boolean retryable, boolean uncertain) {
			super("WhatsApp delivery failed");
			this.retryable = retryable;
			this.uncertain = uncertain;
		}
	}
}
