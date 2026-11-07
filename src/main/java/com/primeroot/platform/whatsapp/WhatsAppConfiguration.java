package com.primeroot.platform.whatsapp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
@EnableConfigurationProperties(WhatsAppProperties.class)
@EnableScheduling
public class WhatsAppConfiguration {
}
