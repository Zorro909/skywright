package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.pricing.GpuComputePriceQuery;
import de.zorro909.skywright.backend.pricing.GpuComputePriceResult;
import de.zorro909.skywright.backend.pricing.OperatorGpuComputePriceSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class GpuPriceScheduleApiIT {

	@Test
	void schedulesRetainBoundariesGapsFutureRatesAndExactBillingRulesAcrossRestart() throws Exception {
		try (var backend = BackendFixture.start()) {
			String offeringId = jsonString(backend.post("/api/v1/eligible-gpu-offerings", offering()).body(), "id");
			var createdSource = backend.post("/api/v1/price-sources", registration());
			assertThat(createdSource.statusCode()).as(createdSource.body()).isEqualTo(201);
			String sourceId = jsonString(createdSource.body(), "id");
			String schedulePath = "/api/v1/price-sources/" + sourceId + "/gpu-price-schedule-entries";

			var rejectedAuthorization = backend.post(schedulePath,
					entry(offeringId, "2", "1", "1", "2029-10-01T00:00:00Z", "2029-11-01T00:00:00Z",
							"2029-09-30T18:00:00Z")
						.replace("\"source\": \"operator tariff\", \"documentRevision\": \"2029-12\"",
								"\"authorization\": \"do-not-store\""));
			assertThat(rejectedAuthorization.statusCode()).as(rejectedAuthorization.body()).isEqualTo(400);
			assertThat(rejectedAuthorization.body()).contains("SKYWRIGHT_HTTP_BAD_REQUEST")
				.doesNotContain("do-not-store");
			var rejectedAccessKey = backend.post(schedulePath,
					entry(offeringId, "2", "1", "1", "2029-10-01T00:00:00Z", "2029-11-01T00:00:00Z",
							"2029-09-30T18:00:00Z")
						.replace("\"source\": \"operator tariff\", \"documentRevision\": \"2029-12\"",
								"\"access_key\": \"do-not-store\""));
			assertThat(rejectedAccessKey.statusCode()).as(rejectedAccessKey.body()).isEqualTo(400);
			assertThat(rejectedAccessKey.body()).contains("SKYWRIGHT_HTTP_BAD_REQUEST").doesNotContain("do-not-store");
			assertThat(backend.get(schedulePath).body()).doesNotContain("do-not-store", "authorization", "access_key");

			var first = backend.post(schedulePath, entry(offeringId, "2.3400", "0.250", "0.016666666666666666",
					"2030-01-01T00:00:00Z", "2030-02-01T00:00:00Z", "2029-12-31T18:00:00Z"));
			assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
			assertThat(first.body()).contains("\"revision\":1", "\"value\":2.3400", "\"minimumQuantity\":0.250",
					"\"billingQuantum\":0.016666666666666666", "operator tariff");
			String firstEntryId = jsonString(first.body(), "id");

			var adjacent = backend.post(schedulePath, entry(offeringId, "2.5000", "1", "0.25", "2030-02-01T00:00:00Z",
					"2030-03-01T00:00:00Z", "2030-01-31T18:00:00Z"));
			assertThat(adjacent.statusCode()).as(adjacent.body()).isEqualTo(201);
			var futureAfterGap = backend.post(schedulePath, entry(offeringId, "2.7500", "1", "1",
					"2030-04-01T00:00:00Z", "2030-05-01T00:00:00Z", "2030-03-31T18:00:00Z"));
			assertThat(futureAfterGap.statusCode()).as(futureAfterGap.body()).isEqualTo(201);

			var overlap = backend.post(schedulePath, entry(offeringId, "99", "1", "1", "2030-01-31T23:59:59Z",
					"2030-02-02T00:00:00Z", "2030-01-31T18:00:00Z"));
			assertThat(overlap.statusCode()).as(overlap.body()).isEqualTo(409);
			assertThat(overlap.body()).contains("SKYWRIGHT_GPU_PRICE_SCHEDULE_OVERLAP");
			assertConcurrentOverlapIsRejected(backend, schedulePath, offeringId);

			var staleUpdate = backend.put(schedulePath + "/" + firstEntryId, update(entry(offeringId, "3", "1", "1",
					"2030-01-01T00:00:00Z", "2030-02-01T00:00:00Z", "2029-12-31T18:00:00Z"), 2));
			assertThat(staleUpdate.statusCode()).isEqualTo(409);
			assertThat(staleUpdate.body()).contains("SKYWRIGHT_GPU_PRICE_SCHEDULE_REVISION_CONFLICT");

			var assessed = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(assessed.statusCode()).as(assessed.body()).isEqualTo(200);
			assertThat(assessed.body()).contains("\"successful\":true", "passed:gpu-compute-rates");

			var postAssessmentChange = backend.post(schedulePath, entry(offeringId, "3.1000", "1", "1",
					"2030-08-01T00:00:00Z", "2030-09-01T00:00:00Z", "2030-07-31T18:00:00Z"));
			assertThat(postAssessmentChange.statusCode()).as(postAssessmentChange.body()).isEqualTo(201);
			var staleAssessmentPromotion = backend.put("/api/v1/price-sources/" + sourceId + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			assertThat(staleAssessmentPromotion.statusCode()).as(staleAssessmentPromotion.body()).isEqualTo(422);
			assertThat(staleAssessmentPromotion.body()).contains("SKYWRIGHT_PRICE_SOURCE_ASSESSMENT_REQUIRED");
			var reassessed = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(reassessed.statusCode()).as(reassessed.body()).isEqualTo(200);
			assertThat(reassessed.body()).contains("\"successful\":true", "passed:gpu-compute-rates");

			assertContractOutcomes(backend, sourceId, offeringId);
			backend.restart();
			assertThat(backend.get(schedulePath).body()).contains(firstEntryId, "2.3400", "2.5000", "2.7500")
				.doesNotContain("\"value\":99");
			assertContractOutcomes(backend, sourceId, offeringId);
		}
	}

	private static void assertConcurrentOverlapIsRejected(BackendFixture backend, String schedulePath,
			String offeringId) throws Exception {
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> {
				start.await();
				return backend.post(schedulePath, entry(offeringId, "4.1000", "1", "1", "2030-06-01T00:00:00Z",
						"2030-07-01T00:00:00Z", "2030-05-31T18:00:00Z"));
			});
			var second = executor.submit(() -> {
				start.await();
				return backend.post(schedulePath, entry(offeringId, "4.2000", "1", "1", "2030-06-15T00:00:00Z",
						"2030-07-15T00:00:00Z", "2030-06-14T18:00:00Z"));
			});
			start.countDown();
			assertThat(List.of(first.get().statusCode(), second.get().statusCode()).stream().sorted().toList())
				.isEqualTo(List.of(201, 409));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void assertContractOutcomes(BackendFixture backend, String sourceId, String offeringId) {
		var source = backend.bean(OperatorGpuComputePriceSource.class);
		var atStart = source.price(query(sourceId, offeringId, "2030-01-01T00:00:00Z", Duration.ofHours(6)));
		assertThat(atStart.outcome()).isEqualTo(GpuComputePriceResult.Outcome.AVAILABLE);
		assertThat(atStart.rate().value()).isEqualByComparingTo("2.3400");

		var atBoundary = source.price(query(sourceId, offeringId, "2030-02-01T00:00:00Z", Duration.ofHours(6)));
		assertThat(atBoundary.outcome()).isEqualTo(GpuComputePriceResult.Outcome.AVAILABLE);
		assertThat(atBoundary.rate().value()).isEqualByComparingTo("2.5000");

		assertThat(source.price(query(sourceId, offeringId, "2030-03-15T00:00:00Z", Duration.ofDays(90))).outcome())
			.isEqualTo(GpuComputePriceResult.Outcome.MISSING);
		assertThat(source.price(query(sourceId, offeringId, "2029-12-31T23:59:59Z", Duration.ofDays(1))).outcome())
			.isEqualTo(GpuComputePriceResult.Outcome.MISSING);
		assertThat(source.price(query(sourceId, offeringId, "2030-01-01T00:00:00Z", Duration.ofHours(5))).outcome())
			.isEqualTo(GpuComputePriceResult.Outcome.STALE);
	}

	private static GpuComputePriceQuery query(String sourceId, String offeringId, String quoteTime, Duration age) {
		return new GpuComputePriceQuery(java.util.UUID.fromString(sourceId), 1, java.util.UUID.fromString(offeringId),
				"aws", "us-east-1", "p5.48xlarge", "H100", 8, false, Instant.parse(quoteTime), age);
	}

	private static String registration() {
		return """
				{
				  "name": "GPU operator schedule",
				  "kind": "operator-schedule",
				  "configuration": {
				    "capabilities": ["gpu-compute"],
				    "nativeCurrencies": ["USD"],
				    "nativeUnits": ["instance-hour"]
				  }
				}
				""";
	}

	private static String offering() {
		return """
				{
				  "targetClass": "cloud-on-demand",
				  "target": "aws",
				  "providerOfferingId": "p5.48xlarge-us-east-1",
				  "region": "us-east-1",
				  "instanceType": "p5.48xlarge",
				  "gpuModel": "H100",
				  "gpuCount": 8,
				  "gpuMemoryBytes": 85899345920,
				  "purchaseMode": "on-demand",
				  "supportTier": "first-class"
				}
				""";
	}

	private static String entry(String offeringId, String value, String minimum, String quantum, String from,
			String until, String observedAt) {
		return """
				{
				  "sourceRevision": 1,
				  "offeringId": "%s",
				  "nativeCurrency": "USD",
				  "nativeUnit": "instance-hour",
				  "value": %s,
				  "minimumQuantity": %s,
				  "billingQuantum": %s,
				  "provenance": {"source": "operator tariff", "documentRevision": "2029-12"},
				  "observedAt": "%s",
				  "effectiveFrom": "%s",
				  "effectiveUntil": "%s"
				}
				""".formatted(offeringId, value, minimum, quantum, observedAt, from, until);
	}

	private static String update(String entry, long expectedRevision) {
		return entry.replaceFirst("\\{", "{\"expectedRevision\":" + expectedRevision + ",");
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
