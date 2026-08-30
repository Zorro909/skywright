package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.pricing.SkyPilotCatalogue;
import de.zorro909.skywright.backend.pricing.SkyPilotCatalogueObservation;
import de.zorro909.skywright.backend.rundefinition.DirectCurrencyCostQuoteReader;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionFailure;
import de.zorro909.skywright.backend.rundefinition.TargetRequest;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Tag("real-service")
final class SkyPilotPriceSourceIT {

	private static final Instant QUOTE_TIME = Instant.parse("2030-01-15T12:00:00Z");

	@Test
	void assessesConfiguredOfferingsAndQuotesOnlyThroughTheExplicitSkyPilotBinding() throws Exception {
		try (var backend = BackendFixture.startWith(CatalogueConfiguration.class)) {
			String firstOffering = createOffering(backend, "p5.48xlarge", "us-east-1");
			String secondOffering = createOffering(backend, "p5.4xlarge", "eu-west-1");
			createInadmissibleOffering(backend);
			var created = backend.post("/api/v1/price-sources", registration());
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
			String sourceId = jsonString(created.body(), "id");

			var assessed = backend.post("/api/v1/price-sources/" + sourceId + "/assessment", "");
			assertThat(assessed.statusCode()).as(assessed.body()).isEqualTo(200);
			assertThat(assessed.body()).contains("\"successful\":true", "passed:gpu-compute",
					"passed:native-currency:USD", "passed:native-unit:instance-hour",
					"passed:target:aws:resource:gpu-compute", "passed:gpu-offering:" + firstOffering + ":revision:1",
					"passed:gpu-offering:" + secondOffering + ":revision:1");
			assertThat(backend
				.put("/api/v1/price-sources/" + sourceId + "/promotion",
						"{\"expectedRegistrationRevision\":2,\"revision\":1}")
				.statusCode()).isEqualTo(200);
			assertThat(
					backend
						.put("/api/v1/price-source-bindings/target:aws:resource:gpu-compute",
								"{\"sourceId\":\"" + sourceId
										+ "\",\"sourceRevision\":1,\"maximumObservationAge\":\"PT6H\"}")
						.statusCode())
				.isEqualTo(200);

			var quotes = backend.bean(DirectCurrencyCostQuoteReader.class);
			var request = new TargetRequest(TargetClass.CLOUD_ON_DEMAND, 1, null, "aws", "H100", null);
			var accepted = quotes.resolve(request, "USD", QUOTE_TIME);
			assertThat(accepted.failures()).isEmpty();
			assertThat(accepted.candidates()).hasSize(2).allSatisfy(candidate -> {
				assertThat(candidate.sourceId().toString()).isEqualTo(sourceId);
				assertThat(candidate.sourceRevision()).isEqualTo(1);
				assertThat(candidate.sourceKind()).isEqualTo("skypilot-catalog");
				assertThat(candidate.nativeRate()).isEqualByComparingTo("2.3400");
				assertThat(candidate.minimumQuantity()).isEqualByComparingTo(BigDecimal.ONE);
				assertThat(candidate.billingQuantum()).isEqualByComparingTo(BigDecimal.ONE);
				assertThat(candidate.nativeCurrency()).isEqualTo("USD");
				assertThat(candidate.nativeUnit()).isEqualTo("instance-hour");
				assertThat(candidate.provenance()).containsEntry("source", "SkyPilot catalogue")
					.containsEntry("valueKind", "estimate");
				assertThat(candidate.rateObservedAt()).isEqualTo(Instant.parse("2030-01-15T11:00:00Z"));
				assertThat(candidate.effectiveFrom()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
				assertThat(candidate.effectiveUntil()).isNull();
			});

			backend.bean(ControllableCatalogue.class).missing = true;
			assertThat(quotes.resolve(request, "USD", QUOTE_TIME).failures()).extracting(RunDefinitionFailure::code)
				.containsExactly("GPU_COMPUTE_PRICE_MISSING", "GPU_COMPUTE_PRICE_MISSING");

			backend.bean(ControllableCatalogue.class).missing = false;
			assertThat(updateOffering(backend, firstOffering).statusCode()).isEqualTo(200);
			var changedOffering = quotes.resolve(request, "USD", QUOTE_TIME);
			assertThat(changedOffering.candidates()).isEmpty();
			assertThat(changedOffering.failures()).singleElement().satisfies(failure -> {
				assertThat(failure.code()).isEqualTo("PRICE_SOURCE_UNAVAILABLE");
				assertThat(failure.details()).containsEntry("offeringId", firstOffering);
			});
		}
	}

	@Test
	void retainsAssessmentEvidenceForLargeOfferingCatalogues() throws Exception {
		try (var backend = BackendFixture.startWith(CatalogueConfiguration.class)) {
			for (int index = 0; index < 70; index++) {
				createOffering(backend, "catalogue-instance-" + index, "catalogue-region-" + index);
			}
			var created = backend.post("/api/v1/price-sources", registration());
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

			var assessed = backend.post("/api/v1/price-sources/" + jsonString(created.body(), "id") + "/assessment",
					"");

			assertThat(assessed.statusCode()).as(assessed.body()).isEqualTo(200);
			assertThat(assessed.body()).contains("\"successful\":true", "passed:target:aws:resource:gpu-compute");
			assertThat(assessed.body().split("passed:gpu-offering:", -1)).hasSize(71);
		}
	}

	private static java.net.http.HttpResponse<String> updateOffering(BackendFixture backend, String offeringId)
			throws Exception {
		return backend.put("/api/v1/eligible-gpu-offerings/" + offeringId, """
				{
				  "expectedRevision": 1,
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
				""");
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

	private static void createInadmissibleOffering(BackendFixture backend) throws Exception {
		var response = backend.post("/api/v1/eligible-gpu-offerings", """
				{
				  "targetClass": "local-single-gpu",
				  "target": "aws",
				  "providerOfferingId": "inert-local-pair",
				  "region": "local",
				  "instanceType": "inert",
				  "gpuModel": "H100",
				  "gpuCount": 1,
				  "gpuMemoryBytes": 85899345920,
				  "purchaseMode": "on-demand",
				  "supportTier": "first-class"
				}
				""");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
	}

	private static String registration() {
		return """
				{
				  "name": "SkyPilot GPU estimates",
				  "kind": "skypilot-catalog",
				  "configuration": {
				    "capabilities": ["gpu-compute"],
				    "targets": ["aws"],
				    "nativeCurrencies": ["USD"],
				    "nativeUnits": ["instance-hour"]
				  }
				}
				""";
	}

	private static String jsonString(String body, String field) {
		return body.replaceFirst("(?s).*?\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

	@Configuration(proxyBeanMethods = false)
	static class CatalogueConfiguration {

		@Bean
		@Primary
		ControllableCatalogue catalogue() {
			return new ControllableCatalogue();
		}

	}

	static final class ControllableCatalogue implements SkyPilotCatalogue {

		private boolean missing;

		@Override
		public Optional<SkyPilotCatalogueObservation> price(
				de.zorro909.skywright.backend.pricing.SkyPilotCatalogueQuery query) {
			if (this.missing || "inert".equals(query.instanceType())) {
				return Optional.empty();
			}
			return Optional.of(new SkyPilotCatalogueObservation(new BigDecimal("2.3400"),
					Map.of("source", "SkyPilot catalogue", "valueKind", "estimate", "target", query.target()),
					Instant.parse("2030-01-15T11:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"), null));
		}

	}

}
