package com.primeroot.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProducePlatformApplicationTests {

	@Autowired
	private DataSource dataSource;

	@LocalServerPort
	private int port;

	@Test
	void contextLoads() throws SQLException {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
		}
	}

	@Test
	void healthEndpointIsAvailableWithoutAuthentication() throws Exception {
		var response = get("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
		assertThat(response.body()).doesNotContain("\"components\"", "\"details\"");
	}

	@Test
	void otherEndpointsRequireAuthentication() throws Exception {
		assertThat(get("/").statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> get(String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Accept", "application/json")
			.timeout(Duration.ofSeconds(10))
			.GET()
			.build();

		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

}
