package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingCatalogue;
import de.zorro909.skywright.backend.rundefinition.CostQuoteSnapshotReader;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionFailure;
import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("real-service")
final class CostQuoteAdmissionIT {

	private static final Instant QUOTE_TIME = Instant.parse("2030-01-15T12:00:00Z");

	@Test
	void resolvesEveryEligibleOfferingOrReturnsOrderedStableFailures() throws Exception {
		try (var backend = BackendFixture.start()) {
			var quotes = backend.bean(CostQuoteSnapshotReader.class);
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

	@Test
	void resolvesDirectAndConvertedCandidatesOrRejectsTheWholeQuote() throws Exception {
		try (var backend = BackendFixture.start()) {
			var quotes = backend.bean(CostQuoteSnapshotReader.class);
			var request = new TargetRequest(TargetClass.CLOUD_ON_DEMAND, 1, null, "aws", "H100", null);
			String directOffering = createOffering(backend, "direct", "eu-west-1");
			String convertedOffering = createOffering(backend, "converted", "us-east-1");
			String gpuSource = jsonString(backend.post("/api/v1/price-sources", mixedCurrencyRegistration()).body(),
					"id");
			String schedule = "/api/v1/price-sources/" + gpuSource + "/gpu-price-schedule-entries";
			backend.post(schedule, entry(directOffering, "2.0000", "1", "1", "EUR", "2030-01-15T11:00:00Z"));
			backend.post(schedule, entry(convertedOffering, "3.0000", "1", "1", "USD", "2030-01-15T11:00:00Z"));
			backend.post("/api/v1/price-sources/" + gpuSource + "/assessment", "");
			backend.put("/api/v1/price-sources/" + gpuSource + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			backend.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute",
					"{\"sourceId\":\"" + gpuSource + "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT1000H\"}");

			var unavailable = quotes.resolve(request, "EUR", QUOTE_TIME);
			assertThat(unavailable.candidates()).isEmpty();
			assertThat(unavailable.failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("CURRENCY_CONVERSION_SOURCE_UNAVAILABLE");

			String conversionSource = jsonString(backend.post("/api/v1/price-sources", conversionRegistration()).body(),
					"id");
			backend.post("/api/v1/price-sources/" + conversionSource + "/currency-conversions", conversionEntry());
			backend.post("/api/v1/price-sources/" + conversionSource + "/assessment", "");
			backend.put("/api/v1/price-sources/" + conversionSource + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			backend.put("/api/v1/price-source-bindings/currency:USD:EUR", "{\"sourceId\":\"" + conversionSource
					+ "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT30M\"}");

			var stale = quotes.resolve(request, "EUR", QUOTE_TIME);
			assertThat(stale.candidates()).isEmpty();
			assertThat(stale.failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("CURRENCY_CONVERSION_STALE");
			backend.put("/api/v1/price-source-bindings/currency:USD:EUR", "{\"sourceId\":\"" + conversionSource
					+ "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT6H\",\"expectedBindingRevision\":1}");

			var accepted = quotes.resolve(request, "EUR", QUOTE_TIME);
			assertThat(accepted.failures()).isEmpty();
			assertThat(accepted.candidates()).hasSize(2);
			var direct = accepted.candidates()
				.stream()
				.filter(candidate -> candidate.offeringId().toString().equals(directOffering))
				.findFirst()
				.orElseThrow();
			var converted = accepted.candidates()
				.stream()
				.filter(candidate -> candidate.offeringId().toString().equals(convertedOffering))
				.findFirst()
				.orElseThrow();
			assertThat(direct.conversion()).isNull();
			assertThat(converted.nativeRate()).isEqualByComparingTo("3.0000");
			assertThat(converted.nativeCurrency()).isEqualTo("USD");
			assertThat(converted.conversion().rate()).isEqualByComparingTo("0.500000");
			assertThat(converted.conversion().nativeCurrency()).isEqualTo("USD");
			assertThat(converted.conversion().reportingCurrency()).isEqualTo("EUR");
			assertThat(converted.conversion().sourceId().toString()).isEqualTo(conversionSource);
			assertThat(converted.conversion().sourceRevision()).isEqualTo(1);
			assertThat(converted.conversion().provenance()).isEqualTo("ECB reference data");
			assertThat(converted.conversion().effectiveFrom()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
			assertThat(converted.conversion().effectiveUntil()).isEqualTo(Instant.parse("2030-01-20T00:00:00Z"));
			assertThat(converted.conversion().sourceObservedFrom()).isNotNull();
			assertThat(converted.conversion().sourceObservedUntil()).isNotNull();

			var missing = quotes.resolve(request, "EUR", Instant.parse("2030-01-25T12:00:00Z"));
			assertThat(missing.candidates()).isEmpty();
			assertThat(missing.failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("CURRENCY_CONVERSION_MISSING");
			assertThat(accepted.candidates()
				.stream()
				.filter(candidate -> candidate.offeringId().toString().equals(convertedOffering))
				.findFirst()
				.orElseThrow()
				.conversion()
				.rate()).isEqualByComparingTo("0.500000");
		}
	}

	@Test
	void concurrentPromotionLeavesTheAcceptedSnapshotConsistentAndChangesTheNextQuote() throws Exception {
		try (var backend = BackendFixture.start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var quotes = backend.bean(CostQuoteSnapshotReader.class);
			var catalogue = backend.bean(EligibleGpuOfferingCatalogue.class);
			var request = new TargetRequest(TargetClass.CLOUD_ON_DEMAND, 1, null, "aws", "H100", null);
			String directOffering = createOffering(backend, "direct-snapshot", "eu-west-1");
			String convertedOffering = createOffering(backend, "converted-snapshot", "us-east-1");
			String gpuSource = jsonString(backend.post("/api/v1/price-sources", mixedCurrencyRegistration()).body(),
					"id");
			String schedule = "/api/v1/price-sources/" + gpuSource + "/gpu-price-schedule-entries";
			backend.post(schedule, entry(directOffering, "2.0000", "1", "1", "EUR", "2030-01-15T11:00:00Z"));
			backend.post(schedule, entry(convertedOffering, "3.0000", "1", "1", "USD", "2030-01-15T11:00:00Z"));
			backend.post("/api/v1/price-sources/" + gpuSource + "/assessment", "");
			backend.put("/api/v1/price-sources/" + gpuSource + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			backend.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute",
					"{\"sourceId\":\"" + gpuSource + "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT1000H\"}");
			String conversionSource = jsonString(backend.post("/api/v1/price-sources", conversionRegistration()).body(),
					"id");
			backend.post("/api/v1/price-sources/" + conversionSource + "/currency-conversions", conversionEntry());
			backend.post("/api/v1/price-sources/" + conversionSource + "/assessment", "");
			backend.put("/api/v1/price-sources/" + conversionSource + "/promotion",
					"{\"expectedRegistrationRevision\":2,\"revision\":1}");
			backend.put("/api/v1/price-source-bindings/currency:USD:EUR", "{\"sourceId\":\"" + conversionSource
					+ "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT6H\"}");

			backend.post("/api/v1/price-sources/" + gpuSource + "/revisions", """
					{
					  "expectedRegistrationRevision": 3,
					  "configuration": {
					    "capabilities": ["gpu-compute"],
					    "nativeCurrencies": ["EUR", "USD"],
					    "nativeUnits": ["instance-hour"]
					  }
					}
					""");
			backend.post(schedule, entry(2, directOffering, "4.0000", "1", "1", "EUR", "2030-01-15T11:00:00Z"));
			backend.post(schedule, entry(2, convertedOffering, "6.0000", "1", "1", "USD", "2030-01-15T11:00:00Z"));
			backend.post("/api/v1/price-sources/" + gpuSource + "/assessment", "");

			var snapshotReady = new CountDownLatch(1);
			var continueResolution = new CountDownLatch(1);
			var transaction = new TransactionTemplate(backend.bean(PlatformTransactionManager.class));
			transaction.setReadOnly(true);
			transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			var resolving = executor.submit(() -> transaction.execute(status -> {
				catalogue.eligible(request);
				snapshotReady.countDown();
				await(continueResolution);
				return quotes.resolve(request, "EUR", QUOTE_TIME);
			}));
			assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();

			var promoted = backend.put("/api/v1/price-sources/" + gpuSource + "/promotion",
					"{\"expectedRegistrationRevision\":5,\"revision\":2}");
			assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
			continueResolution.countDown();

			var accepted = resolving.get(10, TimeUnit.SECONDS);
			assertThat(accepted.failures()).isEmpty();
			assertThat(accepted.candidates())
				.allSatisfy(candidate -> assertThat(candidate.sourceRevision()).isEqualTo(1));
			backend.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute", "{\"sourceId\":\"" + gpuSource
					+ "\",\"sourceRevision\":2,\"maximumObservationAge\":\"PT1000H\",\"expectedBindingRevision\":1}");

			var later = quotes.resolve(request, "EUR", QUOTE_TIME);
			assertThat(later.failures()).isEmpty();
			assertThat(later.candidates()).allSatisfy(candidate -> assertThat(candidate.sourceRevision()).isEqualTo(2));
			assertThat(later.candidates()).extracting(candidate -> candidate.nativeRate().toPlainString())
				.containsExactly("4.0000", "6.0000");
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the concurrent quote test");
			}
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during the concurrent quote test", failure);
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

	private static String mixedCurrencyRegistration() {
		return """
				{
				  "name": "Mixed currency GPU schedule",
				  "kind": "operator-schedule",
				  "configuration": {
				    "capabilities": ["gpu-compute"],
				    "nativeCurrencies": ["EUR", "USD"],
				    "nativeUnits": ["instance-hour"]
				  }
				}
				""";
	}

	private static String conversionRegistration() {
		return """
				{
				  "name": "USD to EUR schedule",
				  "kind": "operator-schedule",
				  "configuration": {
				    "capabilities": ["currency:USD:EUR"],
				    "rates": [{"amount": "0.500000", "currency": "EUR"}]
				  }
				}
				""";
	}

	private static String conversionEntry() {
		return """
				{
				  "expectedScheduleRevision": 0,
				  "nativeCurrency": "USD",
				  "reportingCurrency": "EUR",
				  "rate": "0.500000",
				  "provenance": "ECB reference data",
				  "observedAt": "2030-01-15T11:00:00Z",
				  "effectiveFrom": "2030-01-01T00:00:00Z",
				  "effectiveUntil": "2030-01-20T00:00:00Z"
				}
				""";
	}

	private static String entry(String offeringId, String value, String minimum, String quantum, String observedAt) {
		return entry(offeringId, value, minimum, quantum, "EUR", observedAt);
	}

	private static String entry(String offeringId, String value, String minimum, String quantum, String currency,
			String observedAt) {
		return entry(1, offeringId, value, minimum, quantum, currency, observedAt);
	}

	private static String entry(long sourceRevision, String offeringId, String value, String minimum, String quantum,
			String currency, String observedAt) {
		return """
				{
				  "sourceRevision": %d,
				  "offeringId": "%s",
				  "nativeCurrency": "%s",
				  "nativeUnit": "instance-hour",
				  "value": %s,
				  "minimumQuantity": %s,
				  "billingQuantum": %s,
				  "provenance": {"schedule": "operator-2030-01"},
				  "observedAt": "%s",
				  "effectiveFrom": "2030-01-01T00:00:00Z",
				  "effectiveUntil": "2030-02-01T00:00:00Z"
				}
				""".formatted(sourceRevision, offeringId, currency, value, minimum, quantum, observedAt);
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
