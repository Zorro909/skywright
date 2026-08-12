package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

final class OpenApiBoundaryIT {
  private static final String CORRELATION_HEADER = "X-Correlation-ID";

  @Test
  void canonicalContractIsPackagedAndAvailableUnchangedForDiscovery() throws Exception {
    var canonicalContract =
        OpenApiBoundaryIT.class
            .getResourceAsStream("/META-INF/openapi/skywright-api.yaml")
            .readAllBytes();

    try (var backend = BackendFixture.start()) {
      var response = backend.get("/openapi/skywright-api.yaml");

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(canonicalContract);
    }
  }

  @Test
  void validIncomingCorrelationIdentifierIsEchoed() throws Exception {
    try (var backend = BackendFixture.start()) {
      var response = backend.get("/openapi/skywright-api.yaml", CORRELATION_HEADER, "build.92:1");

      assertThat(response.headers().firstValue(CORRELATION_HEADER)).contains("build.92:1");
    }
  }

  @Test
  void missingOrInvalidCorrelationIdentifierIsReplacedWithUuid() throws Exception {
    try (var backend = BackendFixture.start()) {
      var missing = backend.get("/openapi/skywright-api.yaml");
      var invalid = backend.get("/openapi/skywright-api.yaml", CORRELATION_HEADER, "not/valid");
      var tooLong = backend.get("/openapi/skywright-api.yaml", CORRELATION_HEADER, "a".repeat(65));
      var missingCorrelation = missing.headers().firstValue(CORRELATION_HEADER).orElseThrow();
      var invalidCorrelation = invalid.headers().firstValue(CORRELATION_HEADER).orElseThrow();
      var tooLongCorrelation = tooLong.headers().firstValue(CORRELATION_HEADER).orElseThrow();

      assertCanonicalUuid(missingCorrelation);
      assertCanonicalUuid(invalidCorrelation);
      assertCanonicalUuid(tooLongCorrelation);
      assertThat(invalidCorrelation).isNotEqualTo("not/valid");
    }
  }

  @Test
  void boundaryFailureIsSafeCorrelatedProblemDetail() throws Exception {
    try (var backend = BackendFixture.start()) {
      var response =
          backend.get("/api/v1/not-there?internal=do-not-echo", CORRELATION_HEADER, "request-92");
      var problem = new ObjectMapper().readTree(response.body());

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
      assertThat(response.headers().firstValue(CORRELATION_HEADER)).contains("request-92");
      assertThat(problem.get("type").asText()).isEqualTo("about:blank");
      assertThat(problem.get("title").asText()).isEqualTo("Not Found");
      assertThat(problem.get("status").asInt()).isEqualTo(404);
      assertThat(problem.get("detail").asText()).isEqualTo("No HTTP resource exists at this path.");
      assertThat(problem.get("instance").asText()).isEqualTo("/api/v1/not-there");
      assertThat(problem.get("errorCode").asText()).isEqualTo("SKYWRIGHT_HTTP_NOT_FOUND");
      assertThat(problem.get("correlationId").asText()).isEqualTo("request-92");
      assertThat(problem.get("fieldViolations").isArray()).isTrue();
      assertThat(problem.get("fieldViolations").isEmpty()).isTrue();
      assertThat(response.body()).doesNotContain("do-not-echo", "Exception", "stackTrace");
    }
  }

  private static void assertCanonicalUuid(String value) {
    assertThat(UUID.fromString(value).toString()).isEqualTo(value);
  }
}
