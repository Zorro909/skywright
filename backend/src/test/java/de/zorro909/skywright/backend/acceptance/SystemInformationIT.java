package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class SystemInformationIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void reportsTheRunningBackendsBuildIdentity() throws Exception {
		try (var backend = BackendFixture.start()) {
			var response = backend.get("/api/v1/system-information");
			var body = JSON.readTree(response.body());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(body.get("apiVersion").asText()).isEqualTo("1.0.0");
			assertThat(body.get("applicationVersion").asText()).isEqualTo("0.1.0-SNAPSHOT");
			assertThat(body.get("sourceRevision").asText()).matches("[0-9a-f]{40}");
		}
	}

	@Test
	void reportsAnUnavailableSourceRevisionAsNull() throws Exception {
		var properties = new Properties();
		properties.setProperty("version", "0.1.0-test");
		try (var backend = BackendFixture.start(new BuildProperties(properties))) {
			var response = backend.get("/api/v1/system-information");
			var body = JSON.readTree(response.body());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(body.get("sourceRevision").isNull()).isTrue();
		}
	}

}
