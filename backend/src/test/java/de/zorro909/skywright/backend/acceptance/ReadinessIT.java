package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ReadinessIT {
  @Test
  void runningBackendIsReadyToAcceptHttpTraffic() throws Exception {
    try (var backend = BackendFixture.start()) {
      var response = backend.get("/readyz");

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"status\":\"UP\"");
    }
  }
}
