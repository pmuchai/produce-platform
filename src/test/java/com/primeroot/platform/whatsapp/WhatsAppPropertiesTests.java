package com.primeroot.platform.whatsapp;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppPropertiesTests {
	@Test
	void documentedEnvironmentVariablesBindAndEnableTheWorkerWithoutExposingCredentials() {
		var environment = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
			"PLATFORM_WHATSAPP_APPSECRET", "0123456789abcdef0123456789abcdef",
			"PLATFORM_WHATSAPP_VERIFYTOKEN", "test-only-verification-token-32-characters",
			"PLATFORM_WHATSAPP_ACCESSTOKEN", "test-only-meta-token",
			"PLATFORM_WHATSAPP_PHONENUMBERID", "123456789",
			"PLATFORM_WHATSAPP_BUSINESSNUMBER", "254700000000",
			"PLATFORM_WHATSAPP_APIVERSION", "v24.0",
			"PLATFORM_WHATSAPP_ENCRYPTIONKEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
		var properties = new Binder(ConfigurationPropertySources.from(environment))
			.bind("platform.whatsapp", Bindable.of(WhatsAppProperties.class)).get();
		assertThat(properties.workerEnabled()).isTrue();
		assertThat(properties.phoneNumberId()).isEqualTo("123456789");
		assertThat(properties.toString()).doesNotContain(properties.appSecret(), properties.accessToken(), properties.encryptionKey());
	}
}
