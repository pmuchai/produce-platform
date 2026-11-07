package com.primeroot.platform.whatsapp;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppTransportTests {
	private final JsonMapper json = JsonMapper.builder().build();
	private final WhatsAppProperties properties = new WhatsAppProperties("0123456789abcdef0123456789abcdef",
		"test-only-verification-token-32-characters", "test-only-meta-token", "123456789", "254700000000",
		"v24.0", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", false);

	@Test
	@SuppressWarnings("unchecked")
	void sendsCloudApiTextToFixedHttpsEndpointWithBoundedTextAndTimeout() throws Exception {
		var client = mock(HttpClient.class);
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		var transport = new WhatsAppTransport(properties, json, client);
		transport.send("254711111111", "Net sales: KES 123.45");
		var capture = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client).send(capture.capture(), any(HttpResponse.BodyHandler.class));
		var request = capture.getValue();
		assertThat(request.uri().toString()).isEqualTo("https://graph.facebook.com/v24.0/123456789/messages");
		assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-only-meta-token");
		assertThat(request.timeout().orElseThrow().toSeconds()).isEqualTo(10);
		var body = json.readTree(body(request));
		assertThat(body.path("messaging_product").asText()).isEqualTo("whatsapp");
		assertThat(body.path("to").asText()).isEqualTo("254711111111");
		assertThat(body.path("text").path("body").asText()).isEqualTo("Net sales: KES 123.45");
		assertThat(body.path("text").path("preview_url").asBoolean()).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void rateLimitsMayRetryButAmbiguousSendsCannotBeAutomaticallyRepeated() throws Exception {
		var client = mock(HttpClient.class);
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		var transport = new WhatsAppTransport(properties, json, client);
		when(response.statusCode()).thenReturn(429);
		assertThatThrownBy(() -> transport.send("254711111111", "help"))
			.isInstanceOfSatisfying(WhatsAppTransport.DeliveryFailure.class, failure -> {
				assertThat(failure.retryable).isTrue();
				assertThat(failure.uncertain).isFalse();
			});
		when(response.statusCode()).thenReturn(503);
		assertThatThrownBy(() -> transport.send("254711111111", "help"))
			.isInstanceOfSatisfying(WhatsAppTransport.DeliveryFailure.class, failure -> {
				assertThat(failure.retryable).isFalse();
				assertThat(failure.uncertain).isTrue();
			});
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("test timeout"));
		assertThatThrownBy(() -> transport.send("254711111111", "help"))
			.isInstanceOfSatisfying(WhatsAppTransport.DeliveryFailure.class, failure -> assertThat(failure.uncertain).isTrue());
	}

	private static String body(HttpRequest request) {
		var bytes = new ByteArrayOutputStream();
		var result = new CompletableFuture<String>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
			@Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
			@Override public void onNext(ByteBuffer buffer) {
				var chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			@Override public void onError(Throwable exception) { result.completeExceptionally(exception); }
			@Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
		});
		return result.join();
	}
}
