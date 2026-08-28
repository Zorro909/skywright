package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.rundefinition.DirectCurrencyCostQuoteReader;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionFailure;
import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class CostQuoteAdmissionIT {

	private static final Instant QUOTE_TIME = Instant.parse("2030-01-15T12:00:00Z");

	@Test
	void resolvesEveryEligibleOfferingOrReturnsOrderedStableFailures() throws Exception {
		try (var backend = BackendFixture.start()) {
			var quotes = backend.bean(DirectCurrencyCostQuoteReader.class);
			var request = new TargetRequest(TargetClass.CLOUD_ON_DEMAND, 1, null, "aws", "H100", null);

			assertThat(quotes.resolve(request, "EUR", QUOTE_TIME).failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("GPU_OFFERING_NONE_ELIGIBLE");

			String firstOffering = createOffering(backend, "p5.48xlarge", "us-east-1");
			String secondOffering = createOffering(backend, "p5.4xlarge", "eu-west-1");
			assertThat(quotes.resolve(request, "EUR", QUOTE_TIME).failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("PRICE_SOURCE_UNAVAILABLE", "PRICE_SOURCE_UNAVAILABLE");

			String sourceId = jsonString(backend.post("/api/v1/price-sources", registration()).body(), "id");
			String schedule = "/api/v1/price-sources/" + sourceId + "/gpu-price-schedule-entries";
			backend.post(schedule, entry(firstOffering, "2.34001", "1", "0.25", "2030-01-15T11:00:00Z"));
			backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			backend.put("/api/v1/price-sources/" + sourceId + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			backend.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute",
					"{\"sourceId\":\"" + sourceId + "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT6H\"}");

			assertThat(quotes.resolve(request, "EUR", QUOTE_TIME).failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("GPU_COMPUTE_PRICE_MISSING");

			var stale = backend.post(schedule, entry(secondOffering, "1.99999", "3", "2", "2030-01-15T05:59:59Z"));
			String staleEntry = jsonString(stale.body(), "id");
			assertThat(quotes.resolve(request, "EUR", QUOTE_TIME).failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("GPU_COMPUTE_PRICE_STALE");

			backend.put(schedule + "/" + staleEntry, entry(secondOffering, "1.99999", "3", "2", "2030-01-15T06:00:00Z")
				.replaceFirst("\\{", "{\"expectedRevision\":1,"));
			var accepted = quotes.resolve(request, "EUR", QUOTE_TIME);
			assertThat(accepted.failures()).isEmpty();
			assertThat(accepted.candidates()).extracting(candidate -> candidate.offeringId().toString())
				.containsExactly(secondOffering, firstOffering);
			assertThat(accepted.candidates()).allSatisfy(candidate -> {
				assertThat(candidate.nativeCurrency()).isEqualTo("EUR");
				assertThat(candidate.nativeUnit()).isEqualTo("instance-hour");
				assertThat(candidate.sourceId().toString()).isEqualTo(sourceId);
				assertThat(candidate.sourceRevision()).isEqualTo(1);
				assertThat(candidate.maximumObservationAge()).isEqualTo(java.time.Duration.ofHours(6));
			});
		}
	}

	private static String createOffering(BackendFixture backend, String instanceType, String region) throws Exception {
		return jsonString(backend.post("/api/v1/eligible-gpu-offerings", """
				{
				  "targetClass": "cloud-on-demand",
				  "target": "aws",
				  "providerOfferingId": "%s-%s",
				  "region": "%s",
				  "instanceType": "%s",
				  "gpuModel": "H100",
				  "gpuCount": 8,
				  "gpuMemoryBytes": 85899345920,
				  "purchaseMode": "on-demand",
				  "supportTier": "first-class"
				}
				""".formatted(instanceType, region, region, instanceType)).body(), "id");
	}

	private static String registration() {
		return """
				{
				  "name": "Direct EUR GPU schedule",
				  "kind": "operator-schedule",
				  "configuration": {
				    "capabilities": ["gpu-compute"],
				    "nativeCurrencies": ["EUR"],
				    "nativeUnits": ["instance-hour"]
				  }
				}
				""";
	}

	private static String entry(String offeringId, String value, String minimum, String quantum, String observedAt) {
		return """
				{
				  "sourceRevision": 1,
				  "offeringId": "%s",
				  "nativeCurrency": "EUR",
				  "nativeUnit": "instance-hour",
				  "value": %s,
				  "minimumQuantity": %s,
				  "billingQuantum": %s,
				  "provenance": {"schedule": "operator-2030-01"},
				  "observedAt": "%s",
				  "effectiveFrom": "2030-01-01T00:00:00Z",
				  "effectiveUntil": "2030-02-01T00:00:00Z"
				}
				""".formatted(offeringId, value, minimum, quantum, observedAt);
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
