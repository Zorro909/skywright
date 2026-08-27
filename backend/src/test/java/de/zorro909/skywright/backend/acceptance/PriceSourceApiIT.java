package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class PriceSourceApiIT {

	@Test
	void revisionsAssessmentsPromotionsAndBindingsSurviveRestart() throws Exception {
		try (var backend = BackendFixture.start()) {
			var rejectedSecret = backend.post("/api/v1/price-sources", registration("secret schedule",
					"{\"capabilities\":[\"compute\"],\"rates\":[{}],\"apiToken\":\"do-not-store\"}"));
			assertThat(rejectedSecret.statusCode()).isEqualTo(422);
			assertThat(rejectedSecret.body()).contains("SKYWRIGHT_PRICE_SOURCE_SECRET_FORBIDDEN")
				.doesNotContain("do-not-store");

			var created = backend.post("/api/v1/price-sources", registration("operator schedule",
					"{\"capabilities\":[\"compute\"],\"rates\":[{\"amount\":\"2.3400\",\"currency\":\"USD\"}]}"));
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
			assertThat(created.body()).contains("\"candidateRevision\":1", "\"activeRevision\":null")
				.doesNotContain("secret", "password", "token");
			String sourceId = jsonString(created.body(), "id");

			var premature = backend.put("/api/v1/price-sources/" + sourceId + "/promotion",
					"{\"expectedRegistrationRevision\":1,\"revision\":1}");
			assertThat(premature.statusCode()).isEqualTo(422);
			assertThat(premature.body()).contains("SKYWRIGHT_PRICE_SOURCE_ASSESSMENT_REQUIRED");

			var assessed = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(assessed.statusCode()).as(assessed.body()).isEqualTo(200);
			assertThat(assessed.body()).contains("\"successful\":true", "passed:rates", "passed:capabilities");

			var promoted = backend.put("/api/v1/price-sources/" + sourceId + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
			assertThat(promoted.body()).contains("\"activeRevision\":1", "\"candidateRevision\":null");

			var bound = backend.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute",
					"{\"sourceId\":\"" + sourceId + "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT6H\"}");
			assertThat(bound.statusCode()).as(bound.body()).isEqualTo(200);
			assertThat(bound.body()).contains("target:aws:resource:gpu-compute", "\"bindingRevision\":1", "PT6H");

			backend.restart();

			assertThat(backend.get("/api/v1/price-sources").body()).contains(sourceId, "\"activeRevision\":1");
			assertThat(backend.get("/api/v1/price-source-bindings").body()).contains(sourceId,
					"target:aws:resource:gpu-compute", "PT6H");
		}
	}

	private static String registration(String name, String configuration) {
		return "{\"name\":\"" + name + "\",\"kind\":\"operator-schedule\",\"configuration\":" + configuration + "}";
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
