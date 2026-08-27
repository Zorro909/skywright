package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.pricing.CurrencyConversionOutcome;
import de.zorro909.skywright.backend.pricing.PriceSource;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-service")
final class PriceSourceApiIT {

	@Test
	void operatorsManageExactEffectiveDatedCurrencyConversions() throws Exception {
		try (var backend = BackendFixture.start()) {
			String sourceId = createOperatorSchedule(backend, "currency schedule", "currency:USD:EUR");
			var created = backend.post("/api/v1/price-sources/" + sourceId + "/currency-conversions", """
					{
					  "expectedScheduleRevision": 0,
					  "nativeCurrency": "USD",
					  "reportingCurrency": "EUR",
					  "rate": "0.910000",
					  "provenance": "ECB reference data",
					  "observedAt": "2026-08-27T10:00:00Z",
					  "effectiveFrom": "2026-08-27T00:00:00Z",
					  "effectiveUntil": "2026-08-28T00:00:00Z"
					}
					""");
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
			assertThat(created.body()).contains("\"scheduleRevision\":1", "\"rate\":\"0.910000\"",
					"ECB reference data");
			String conversionId = jsonString(created.body(), "id");
			var replaced = backend.put("/api/v1/price-sources/" + sourceId + "/currency-conversions/" + conversionId,
					conversion(1, "0.920000", "2026-08-27T00:00:00Z", "2026-08-28T00:00:00Z"));
			assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
			assertThat(replaced.body()).contains("\"scheduleRevision\":2", "\"rate\":\"0.920000\"");
			var deleted = backend.delete("/api/v1/price-sources/" + sourceId + "/currency-conversions/" + conversionId
					+ "?expectedScheduleRevision=2");
			assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
			assertThat(deleted.body()).contains("\"scheduleRevision\":3", "\"entries\":[]");
			assertThat(backend
				.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(3, "0.910000", "2026-08-27T00:00:00Z", "2026-08-28T00:00:00Z"))
				.statusCode()).isEqualTo(201);

			backend.restart();

			var persisted = backend.get("/api/v1/price-sources/" + sourceId + "/currency-conversions");
			assertThat(persisted.statusCode()).as(persisted.body()).isEqualTo(200);
			assertThat(persisted.body()).contains("\"scheduleRevision\":4", "\"rate\":\"0.910000\"",
					"2026-08-27T00:00:00Z", "2026-08-28T00:00:00Z");
		}
	}

	@Test
	void scheduleRejectsSharedBoundariesOverlapsAndStaleRevisions() throws Exception {
		try (var backend = BackendFixture.start()) {
			String sourceId = createOperatorSchedule(backend, "guarded schedule", "currency:USD:EUR");
			assertThat(backend
				.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(0, "0.91", "2026-08-27T00:00:00Z", "2026-08-27T01:00:00Z"))
				.statusCode()).isEqualTo(201);

			var sharedBoundary = backend.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
					conversion(1, "0.92", "2026-08-27T01:00:00Z", "2026-08-27T02:00:00Z"));
			assertThat(sharedBoundary.statusCode()).as(sharedBoundary.body()).isEqualTo(409);
			assertThat(sharedBoundary.body()).contains("SKYWRIGHT_CURRENCY_CONVERSION_INTERVAL_OVERLAP");

			var staleRevision = backend.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
					conversion(0, "0.93", "2026-08-27T02:00:00Z", "2026-08-27T03:00:00Z"));
			assertThat(staleRevision.statusCode()).as(staleRevision.body()).isEqualTo(409);
			assertThat(staleRevision.body()).contains("SKYWRIGHT_CURRENCY_CONVERSION_SCHEDULE_REVISION_CONFLICT");
		}
	}

	@Test
	void databaseSerializesConcurrentOverlappingScheduleWrites() throws Exception {
		try (var backend = BackendFixture.start(); var requests = Executors.newVirtualThreadPerTaskExecutor()) {
			String sourceId = createOperatorSchedule(backend, "concurrent schedule", "currency:USD:EUR");
			var first = requests
				.submit(() -> backend.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(0, "0.91", "2026-08-27T00:00:00Z", "2026-08-27T02:00:00Z")));
			var second = requests
				.submit(() -> backend.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(0, "0.92", "2026-08-27T01:00:00Z", "2026-08-27T03:00:00Z")));

			assertThat(java.util.List.of(first.get().statusCode(), second.get().statusCode()))
				.containsExactlyInAnyOrder(201, 409);
		}
	}

	@Test
	void priceSourceReturnsQualifyingMissingStaleAndUnavailableOutcomes() throws Exception {
		try (var backend = BackendFixture.start()) {
			String sourceId = createOperatorSchedule(backend, "quote schedule", "currency:USD:EUR");
			PriceSource prices = backend.bean(PriceSource.class);
			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T12:00:00Z")).outcome())
				.isEqualTo(CurrencyConversionOutcome.UNAVAILABLE);

			var rejectedAssessment = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(rejectedAssessment.body()).contains("\"successful\":false", "failed:currency:USD:EUR");

			assertThat(backend
				.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(0, "0.910000", "2026-08-27T10:00:00Z", "2026-08-27T13:00:00Z"))
				.statusCode()).isEqualTo(201);
			assertThat(backend
				.post("/api/v1/price-sources/" + sourceId + "/currency-conversions",
						conversion(1, "0.930000", "2026-08-27T14:00:00Z", "2026-08-27T15:00:00Z"))
				.statusCode()).isEqualTo(201);
			var acceptedAssessment = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(acceptedAssessment.body()).contains("\"successful\":true", "passed:currency:USD:EUR");
			assertThat(backend
				.put("/api/v1/price-sources/" + sourceId + "/promotion",
						"{\"expectedRegistrationRevision\":3,\"revision\":1}")
				.statusCode()).isEqualTo(200);
			assertThat(
					backend
						.put("/api/v1/price-source-bindings/currency:USD:EUR",
								"{\"sourceId\":\"" + sourceId
										+ "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT2H\"}")
						.statusCode())
				.isEqualTo(200);

			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T09:59:59Z")).outcome())
				.isEqualTo(CurrencyConversionOutcome.MISSING);
			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T10:00:00Z")).outcome())
				.isEqualTo(CurrencyConversionOutcome.QUALIFYING);
			var fresh = prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T12:00:00Z"));
			assertThat(fresh.outcome()).isEqualTo(CurrencyConversionOutcome.QUALIFYING);
			assertThat(fresh.rate()).isEqualByComparingTo("0.910000");
			assertThat(
					prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T12:00:00.001Z")).outcome())
				.isEqualTo(CurrencyConversionOutcome.STALE);
			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T13:00:00Z")).rate())
				.isEqualByComparingTo("0.910000");
			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T13:30:00Z")).outcome())
				.isEqualTo(CurrencyConversionOutcome.MISSING);
			assertThat(prices.resolveCurrencyConversion("USD", "EUR", Instant.parse("2026-08-27T14:00:00Z")).rate())
				.isEqualByComparingTo("0.930000");
		}
	}

	@Test
	void revisionsAssessmentsPromotionsAndBindingsSurviveRestart() throws Exception {
		try (var backend = BackendFixture.start()) {
			var rejectedSecret = backend.post("/api/v1/price-sources", registration("secret schedule",
					"{\"capabilities\":[\"compute\"],\"rates\":[{}],\"apiToken\":\"do-not-store\"}"));
			assertThat(rejectedSecret.statusCode()).isEqualTo(422);
			assertThat(rejectedSecret.body()).contains("SKYWRIGHT_PRICE_SOURCE_SECRET_FORBIDDEN")
				.doesNotContain("do-not-store");
			var invalidRate = backend.post("/api/v1/price-sources", registration("invalid schedule",
					"{\"capabilities\":[\"compute\"],\"rates\":[{\"amount\":{},\"currency\":\"ZZZ\"}]}"));
			assertThat(invalidRate.statusCode()).as(invalidRate.body()).isEqualTo(201);
			String invalidSourceId = jsonString(invalidRate.body(), "id");
			var invalidAssessment = backend.post("/api/v1/price-sources/" + invalidSourceId + "/assessment", "");
			assertThat(invalidAssessment.statusCode()).as(invalidAssessment.body()).isEqualTo(200);
			assertThat(invalidAssessment.body()).contains("\"successful\":false", "failed:rates-required");

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

	private static String createOperatorSchedule(BackendFixture backend, String name, String capability)
			throws Exception {
		var created = backend.post("/api/v1/price-sources", registration(name,
				"{\"capabilities\":[\"" + capability + "\"],\"rates\":[{\"amount\":\"1\",\"currency\":\"USD\"}]}"));
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		return jsonString(created.body(), "id");
	}

	private static String conversion(long expectedRevision, String rate, String effectiveFrom, String effectiveUntil) {
		return """
				{
				  "expectedScheduleRevision": %d,
				  "nativeCurrency": "USD",
				  "reportingCurrency": "EUR",
				  "rate": "%s",
				  "provenance": "ECB reference data",
				  "observedAt": "2026-08-27T10:00:00Z",
				  "effectiveFrom": "%s",
				  "effectiveUntil": "%s"
				}
				""".formatted(expectedRevision, rate, effectiveFrom, effectiveUntil);
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

}
