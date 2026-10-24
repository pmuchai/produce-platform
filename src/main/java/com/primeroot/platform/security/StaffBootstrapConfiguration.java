package com.primeroot.platform.security;

import com.primeroot.platform.access.StaffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "platform.bootstrap.enabled", havingValue = "true")
public class StaffBootstrapConfiguration {
	private static final Logger LOG = LoggerFactory.getLogger(StaffBootstrapConfiguration.class);

	@Bean
	ApplicationRunner bootstrapStaff(StaffService staff,
			@Value("${BOOTSTRAP_ADMIN_USERNAME:}") String username,
			@Value("${BOOTSTRAP_ADMIN_FULL_NAME:}") String fullName,
			@Value("${BOOTSTRAP_ADMIN_SUBJECT:}") String subject) {
		return arguments -> {
			if (staff.bootstrap(username, fullName, subject)) {
				LOG.info("Approved the configured initial staff identity; disable bootstrap after setup");
			}
			else {
				LOG.info("Staff bootstrap skipped because the configured identity is already approved");
			}
		};
	}
}
