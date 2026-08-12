package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ManagementExposureIT {

	@Test
	void onlyHealthAndNonSensitiveBuildInformationAreExposed() throws Exception {
		try (var backend = BackendFixture.start()) {
			var info = backend.get("/actuator/info");
			var environment = backend.get("/actuator/env");

			assertThat(info.statusCode()).isEqualTo(200);
			assertThat(info.body()).contains("\"artifact\":\"skywright-backend\"");
			assertThat(environment.statusCode()).isEqualTo(404);
		}
	}

}
