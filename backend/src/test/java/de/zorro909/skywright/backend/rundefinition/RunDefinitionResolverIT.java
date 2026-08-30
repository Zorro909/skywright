package de.zorro909.skywright.backend.rundefinition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import de.zorro909.skywright.backend.configurationcontract.ConfigurationContracts;
import de.zorro909.skywright.backend.metriccontract.MetricContracts;
import de.zorro909.skywright.backend.projectversion.ProjectVersionReference;
import de.zorro909.skywright.backend.projectversion.ProjectVersionRegistry;
import de.zorro909.skywright.backend.projectversion.RegistryArtifact;
import de.zorro909.skywright.backend.projectversion.TrainingProjectBinding;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersions;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSelection;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSnapshot;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import tools.jackson.databind.JsonNode;

class RunDefinitionResolverIT {

	private static final String VERSION_DIGEST = "sha256:" + "9".repeat(64);

	private final ConfigurationContracts configurationContracts = new ConfigurationContracts();

	private final MetricContracts metricContracts = new MetricContracts();

	@Test
	void resolvesACompleteDefinitionThroughThePublicSeam() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");

		RunDefinitionResolution resolution = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null);

		assertThat(resolution.accepted()).isTrue();
		JsonNode definition = resolution.definition().value();
		assertThat(definition.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(definition.at("/trainingProjectVersion/images/cuda").asText()).isEqualTo("sha256:" + "a".repeat(64));
		assertThat(definition.at("/trainingProjectVersion/images/rocm").asText()).isEqualTo("sha256:" + "b".repeat(64));
		assertThat(definition.at("/trainingProjectVersion/environmentProfiles/cuda").asText())
			.isEqualTo("registry.example/environment:cuda@sha256:" + "c".repeat(64));
		assertThat(definition.at("/trainingProjectVersion/environmentProfiles/rocm").asText())
			.isEqualTo("registry.example/environment:rocm@sha256:" + "d".repeat(64));
		assertThat(definition.at("/configuration/reproducibility/seed").asInt()).isEqualTo(9);
		assertThat(definition.at("/configuration/checkpoint/retention").asInt()).isEqualTo(3);
		assertThat(definition.at("/datasetDefinition/datasetIdentity").asText()).isEqualTo("dataset-1");
		assertThat(definition.at("/targetRequest/gpuModel").asText()).isEqualTo("H100");
		assertThat(definition.at("/storage/execution/registrationRevision").asLong()).isEqualTo(7);
		assertThat(definition.at("/storage/repatriation/enabled").asBoolean()).isFalse();
		assertThat(definition.at("/executionPolicy/maximumRecoveryDebt").asInt()).isEqualTo(3);
		assertThat(definition.at("/targetRequest/purchaseMode").asText()).isEqualTo("spot");
		assertThat(definition.at("/executionPolicy/runtimeCeiling").asText()).isEqualTo("PT1H");
		assertThat(definition.at("/executionPolicy/costCeiling/amount").decimalValue()).isEqualByComparingTo("12.3400");
		assertThat(definition.at("/executionPolicy/costCeiling/currency").asText()).isEqualTo("EUR");
		assertThat(definition.at("/costQuote/reportingCurrency/minorUnit").asInt()).isEqualTo(2);
		assertThat(definition.at("/costQuote/candidates/0/nativeRate/amount").decimalValue())
			.isEqualByComparingTo("2.5000");
		assertThat(definition.at("/costQuote/candidates/0/offering/id").asText())
			.isEqualTo("00000000-0000-0000-0000-000000000200");
		assertThat(definition.at("/costQuote/candidates/0/source/revision").asLong()).isEqualTo(3);
		assertThat(definition.at("/costQuote/hourly/minimum").decimalValue()).isEqualByComparingTo("2.50");
		assertThat(definition.at("/costQuote/daily/minimum").decimalValue()).isEqualByComparingTo("60.00");
		assertThat(definition.at("/costQuote/weekly/minimum").decimalValue()).isEqualByComparingTo("420.00");
		assertThat(definition.toString()).doesNotContain("credential", "runId", "checkpointReference", "metricCatalog",
				"lifecycle", "orchestrator", "storageLocation", "datasetCopy");
	}

	@Test
	void appliesBillingMinimumsAndQuantumRoundingForEachQuoteDuration() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget candidate = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteCandidate quoted = withBillingRules(quoteCandidate(candidate, quoteTime), new BigDecimal("3"),
				new BigDecimal("2"));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(candidate), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
				(request, currency, time) -> new CostQuoteAssessment(List.of(quoted), List.of()));

		JsonNode quote = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null)
			.definition()
			.value()
			.path("costQuote");

		assertThat(quote.at("/hourly/minimum").decimalValue()).isEqualByComparingTo("10");
		assertThat(quote.at("/daily/minimum").decimalValue()).isEqualByComparingTo("60");
		assertThat(quote.at("/weekly/minimum").decimalValue()).isEqualByComparingTo("420");
	}

	@Test
	void findsEachDurationRangeBeforeRoundingForTheReportingCurrency() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteCandidate minimumHeavy = withBillingRules(quoteCandidate(target, quoteTime), new BigDecimal("10"),
				BigDecimal.ONE);
		CostQuoteCandidate higherRate = withRate(quoteCandidate(target, quoteTime), new BigDecimal("5.0049"));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(target), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
				(request, currency, time) -> new CostQuoteAssessment(List.of(minimumHeavy, higherRate), List.of()));

		JsonNode quote = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null)
			.definition()
			.value()
			.path("costQuote");

		assertThat(quote.at("/hourly/minimum").decimalValue()).isEqualByComparingTo("5.00");
		assertThat(quote.at("/hourly/maximum").decimalValue()).isEqualByComparingTo("25.00");
		assertThat(quote.at("/daily/minimum").decimalValue()).isEqualByComparingTo("60.00");
		assertThat(quote.at("/daily/maximum").decimalValue()).isEqualByComparingTo("120.12");
		assertThat(quote.at("/weekly/minimum").decimalValue()).isEqualByComparingTo("420.00");
		assertThat(quote.at("/weekly/maximum").decimalValue()).isEqualByComparingTo("840.82");
	}

	@Test
	void roundsZeroTwoAndThreeDecimalCurrenciesOnlyAtPresentation() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		for (Map.Entry<String, String> currency : Map.of("JPY", "1", "EUR", "1.23", "KWD", "1.235").entrySet()) {
			CostQuoteCandidate candidate = withCurrencyAndRate(quoteCandidate(target, quoteTime), currency.getKey(),
					new BigDecimal("1.23456"));
			RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
					new TargetEligibilityAssessment(List.of(target), List.of()), storage(), currency.getKey(),
					Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
					(request, reportingCurrency, time) -> new CostQuoteAssessment(List.of(candidate), List.of()));

			JsonNode quote = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null)
				.definition()
				.value()
				.path("costQuote");
			assertThat(quote.at("/hourly/minimum").decimalValue()).as(currency.getKey())
				.isEqualByComparingTo(currency.getValue());
			assertThat(quote.at("/candidates/0/nativeRate/amount").decimalValue()).as(currency.getKey())
				.isEqualByComparingTo("1.23456");
		}
	}

	@Test
	void combinesDirectAndExactConvertedAmountsBeforePresentationRounding() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteCandidate direct = withRate(quoteCandidate(target, quoteTime), new BigDecimal("2.0000"));
		CostQuoteCandidate converted = withConversion(
				withCurrencyAndRate(quoteCandidate(target, quoteTime), "USD", new BigDecimal("1.0049")),
				new CostQuoteConversion("USD", "EUR", new BigDecimal("1.0049"), "operator conversion",
						UUID.fromString("00000000-0000-0000-0000-000000000300"), 7, "operator-schedule",
						quoteTime.minus(Duration.ofDays(1)), quoteTime.plus(Duration.ofDays(1)),
						quoteTime.minus(Duration.ofMinutes(5)), quoteTime.minus(Duration.ofMinutes(1)), quoteTime,
						Duration.ofHours(1)));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(target), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
				(request, currency, time) -> new CostQuoteAssessment(List.of(direct, converted), List.of()));

		JsonNode quote = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null)
			.definition()
			.value()
			.path("costQuote");

		assertThat(quote.at("/hourly/minimum").decimalValue()).isEqualByComparingTo("1.01");
		assertThat(quote.at("/hourly/maximum").decimalValue()).isEqualByComparingTo("2.00");
		assertThat(quote.at("/daily/minimum").decimalValue()).isEqualByComparingTo("24.24");
		assertThat(quote.at("/weekly/minimum").decimalValue()).isEqualByComparingTo("169.65");
		assertThat(quote.at("/candidates/1/nativeRate/amount").decimalValue()).isEqualByComparingTo("1.0049");
		assertThat(quote.at("/candidates/1/conversion/rate").decimalValue()).isEqualByComparingTo("1.0049");
		assertThat(quote.at("/candidates/1/conversion/nativeCurrency").asText()).isEqualTo("USD");
		assertThat(quote.at("/candidates/1/conversion/reportingCurrency").asText()).isEqualTo("EUR");
		assertThat(quote.at("/candidates/1/conversion/source/revision").asLong()).isEqualTo(7);
		assertThat(quote.at("/candidates/1/conversion/effectiveInterval/from").asText())
			.isEqualTo("2026-08-26T12:00:00Z");
		assertThat(quote.at("/candidates/1/conversion/observationInterval/until").asText())
			.isEqualTo("2026-08-27T12:00:00Z");
	}

	@Test
	void acceptedQuoteRemainsFrozenAfterCatalogueScheduleAndCurrencyChanges() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		AtomicReference<String> currency = new AtomicReference<>("EUR");
		AtomicReference<CostQuoteCandidate> candidate = new AtomicReference<>(quoteCandidate(target, quoteTime));
		RunDefinitionResolver resolver = new RunDefinitionResolver(
				new TrainingProjectVersions(eligibleVersionRegistry(), this.configurationContracts,
						this.metricContracts),
				ignored -> DatasetDefinitionAssessment.accepted(),
				() -> new TargetEligibilityAssessment(List.of(target), List.of()),
				(targetClass, overrides) -> storage(), currency::get,
				(request, reportingCurrency, time) -> new CostQuoteAssessment(List.of(candidate.get()), List.of()),
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC));

		RunDefinition accepted = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).definition();
		String frozen = accepted.encode();
		currency.set("JPY");
		candidate.set(withCurrencyAndRate(candidate.get(), "JPY", new BigDecimal("99.999")));
		RunDefinition later = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).definition();

		assertThat(accepted.encode()).isEqualTo(frozen);
		assertThat(RunDefinition.decode(accepted.encode())).isEqualTo(accepted);
		assertThat(accepted.value().at("/costQuote/reportingCurrency/code").asText()).isEqualTo("EUR");
		assertThat(accepted.value().at("/costQuote/candidates/0/nativeRate/amount").decimalValue())
			.isEqualByComparingTo("2.5000");
		assertThat(later.value().at("/costQuote/reportingCurrency/code").asText()).isEqualTo("JPY");
		assertThat(later.value().at("/costQuote/candidates/0/nativeRate/amount").decimalValue())
			.isEqualByComparingTo("99.999");
	}

	@Test
	void acceptedConversionRemainsFrozenAfterTheSourceChanges() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteConversion firstConversion = new CostQuoteConversion("USD", "EUR", new BigDecimal("0.910000"),
				"first conversion", UUID.fromString("00000000-0000-0000-0000-000000000300"), 4, "operator-schedule",
				quoteTime.minus(Duration.ofDays(1)), quoteTime.plus(Duration.ofDays(1)),
				quoteTime.minus(Duration.ofMinutes(5)), quoteTime.minus(Duration.ofMinutes(1)), quoteTime,
				Duration.ofHours(1));
		AtomicReference<CostQuoteCandidate> candidate = new AtomicReference<>(
				withConversion(withCurrencyAndRate(quoteCandidate(target, quoteTime), "USD", new BigDecimal("2.5000")),
						firstConversion));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(target), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
				(request, currency, time) -> new CostQuoteAssessment(List.of(candidate.get()), List.of()));

		RunDefinition accepted = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).definition();
		String frozen = accepted.encode();
		candidate.set(withConversion(candidate.get(),
				new CostQuoteConversion("USD", "EUR", new BigDecimal("0.800000"), "later conversion",
						UUID.fromString("00000000-0000-0000-0000-000000000300"), 5, "operator-schedule",
						quoteTime.minus(Duration.ofDays(1)), quoteTime.plus(Duration.ofDays(1)),
						quoteTime.minus(Duration.ofMinutes(5)), quoteTime.minus(Duration.ofMinutes(1)), quoteTime,
						Duration.ofHours(1))));
		RunDefinition later = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).definition();

		assertThat(accepted.encode()).isEqualTo(frozen);
		assertThat(RunDefinition.decode(accepted.encode())).isEqualTo(accepted);
		assertThat(accepted.value().at("/costQuote/candidates/0/conversion/rate").decimalValue())
			.isEqualByComparingTo("0.910000");
		assertThat(accepted.value().at("/costQuote/candidates/0/conversion/source/revision").asLong()).isEqualTo(4);
		assertThat(later.value().at("/costQuote/candidates/0/conversion/rate").decimalValue())
			.isEqualByComparingTo("0.800000");
		assertThat(later.value().at("/costQuote/candidates/0/conversion/source/revision").asLong()).isEqualTo(5);
	}

	@Test
	void localAdmissionDoesNotRequireOrResolveACloudCostQuote() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget local = eligibleTarget("local", TargetClass.LOCAL_SINGLE_GPU, "cuda", "H100", 1,
				80L * 1024 * 1024 * 1024);
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(local), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC), (request, currency, time) -> {
					throw new AssertionError("cloud Cost Quote must not be resolved for a local target");
				});

		RunDefinitionResolution resolution = resolver.resolve(submission(TargetClass.LOCAL_SINGLE_GPU), null);

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.definition().value().has("costQuote")).isFalse();
	}

	@Test
	void incompleteQuotesReturnNoDefinitionAndOrderDistinctPriceFailures() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget target = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteReader incomplete = (request, currency, time) -> new CostQuoteAssessment(List.of(),
				List.of(new RunDefinitionFailure("GPU_COMPUTE_PRICE_STALE", "price-source", "/costQuote/candidates/b",
						"complete", Map.of("offeringId", "b")),
						new RunDefinitionFailure("PRICE_SOURCE_UNAVAILABLE", "price-source", "/costQuote/candidates/c",
								"complete", Map.of("offeringId", "c")),
						new RunDefinitionFailure("GPU_COMPUTE_PRICE_MISSING", "price-source", "/costQuote/candidates/a",
								"complete", Map.of("offeringId", "a"))));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(target), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC), incomplete);

		RunDefinitionResolution resolution = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null);

		assertThat(resolution.definition()).isNull();
		assertThat(resolution.failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("GPU_COMPUTE_PRICE_MISSING", "GPU_COMPUTE_PRICE_STALE", "PRICE_SOURCE_UNAVAILABLE");
		assertThat(resolution.failures())
			.allSatisfy(failure -> assertThat(failure.details()).containsKey("offeringId"));
	}

	@Test
	void rejectsRatesOutsideOrWithInvertedEffectiveIntervals() {
		Instant quoteTime = Instant.parse("2026-08-27T12:00:00Z");
		EligibleTarget candidate = eligibleTarget("priced-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8,
				80L * 1024 * 1024 * 1024);
		CostQuoteCandidate invalid = withEffectiveInterval(quoteCandidate(candidate, quoteTime),
				quoteTime.plusSeconds(60), quoteTime.minusSeconds(60));
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(candidate), List.of()), storage(), "EUR",
				Clock.fixed(quoteTime, java.time.ZoneOffset.UTC),
				(request, currency, time) -> new CostQuoteAssessment(List.of(invalid), List.of()));

		assertThat(resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).failures())
			.extracting(RunDefinitionFailure::code)
			.contains("PRICE_NOT_EFFECTIVE", "PRICE_EFFECTIVE_INTERVAL_INVALID");
	}

	@Test
	void acceptsEveryTargetClassWithoutSelectingActualInfrastructure() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");

		for (TargetClass targetClass : TargetClass.values()) {
			RunDefinitionResolution resolution = resolver.resolve(submission(targetClass), null);
			assertThat(resolution.accepted()).as(targetClass + ": " + resolution.failures()).isTrue();
			assertThat(resolution.definition().value().at("/targetRequest/targetClass").asText())
				.isEqualTo(targetClass.wireValue());
		}
		RunDefinitionResolution invalidSingleGpu = resolver.resolve(withTarget(submission(TargetClass.LOCAL_SINGLE_GPU),
				new TargetRequest(TargetClass.LOCAL_SINGLE_GPU, 2, null, null, null, null)), null);
		RunDefinitionResolution invalidMultiGpu = resolver.resolve(withTarget(submission(TargetClass.LOCAL_MULTI_GPU),
				new TargetRequest(TargetClass.LOCAL_MULTI_GPU, 1, null, null, null, null)), null);
		assertThat(invalidSingleGpu.failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("GPU_COUNT_INVALID");
		assertThat(invalidMultiGpu.failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("GPU_COUNT_INVALID");
	}

	@Test
	void returnsIndependentFailuresOnceInStableOrderAndNoPartialDefinition() {
		DatasetDefinitionAssessment datasetFailure = new DatasetDefinitionAssessment(false,
				List.of(new RunDefinitionFailure("DATASET_MISSING", "dataset", "/datasetDefinition", "exists"),
						new RunDefinitionFailure("DATASET_MISSING", "dataset", "/datasetDefinition", "exists")));
		RunDefinitionResolver resolver = resolver(new FakeRegistry(), datasetFailure,
				new TargetEligibilityAssessment(List.of(), List.of()), storage(), "EUR");
		RunSubmission submission = new RunSubmission(new TrainingProjectBinding("stable-project", "registry"),
				VERSION_DIGEST, "{}", dataset(),
				new TargetRequest(TargetClass.CLOUD_SPOT, 0, -1L, "missing", "H100", BigDecimal.ONE),
				RunDefinitionStorageOverrides.none(), 0, Duration.ZERO, BigDecimal.ZERO, true);

		RunDefinitionResolution resolution = resolver.resolve(submission, null);

		assertThat(resolution.definition()).isNull();
		assertThat(resolution.failures()).isSorted().doesNotHaveDuplicates();
		assertThat(resolution.failures()).extracting(RunDefinitionFailure::code)
			.contains("PROJECT_VERSION_MISSING", "DATASET_MISSING", "GPU_COUNT_INVALID", "GPU_MEMORY_INVALID",
					"MINIMUM_THROUGHPUT_UNSUPPORTED", "MAXIMUM_RECOVERY_DEBT_INVALID", "RUNTIME_CEILING_INVALID",
					"COST_CEILING_INVALID", "ORDERING_RESET_REQUIRES_CHECKPOINT");
	}

	@Test
	void nullTargetClassesFailBeforeTargetDependencyLookups() {
		RunDefinitionResolver resolver = new RunDefinitionResolver(
				new TrainingProjectVersions(eligibleVersionRegistry(), this.configurationContracts,
						this.metricContracts),
				ignored -> DatasetDefinitionAssessment.accepted(), () -> {
					throw new AssertionError("target eligibility must not be queried");
				}, (targetClass, overrides) -> {
					throw new AssertionError("Target Storage must not be queried");
				}, () -> "EUR", (request, currency, quoteTime) -> {
					throw new AssertionError("Cost Quote must not be resolved");
				});
		RunSubmission submission = withTarget(submission(TargetClass.CLOUD_SPOT),
				new TargetRequest(null, 1, null, null, "H100", null));

		assertThat(resolver.resolve(submission, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("TARGET_CLASS_INVALID");
	}

	@Test
	void checkpointDatasetChangesRequireAnExplicitResetAndOrderingInputsCannotChange() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunDefinition source = resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).definition();
		RunSubmission changed = new RunSubmission(new TrainingProjectBinding("stable-project", "registry"),
				VERSION_DIGEST,
				"{\"reproducibility\":{\"seed\":10},\"dataset\":{\"ordering\":{\"policy\":\"deterministic-shuffle\"}}}",
				new DatasetDefinitionReference("dataset-1", "v2", "sha256:" + "4".repeat(64)),
				new TargetRequest(TargetClass.CLOUD_SPOT, 2, 80L * 1024 * 1024 * 1024, null, "H100", null),
				RunDefinitionStorageOverrides.none(), null, null, null, false);

		RunDefinitionResolution resolution = resolver.resolve(changed, new CheckpointSeedFacts(source, true));

		assertThat(resolution.failures()).extracting(RunDefinitionFailure::code)
			.contains("CHECKPOINT_ORDERING_SEED_CHANGED", "ORDERING_RESET_REQUIRED");
	}

	@Test
	void unchangedCheckpointDatasetsRejectOrderingReset() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunDefinition source = resolver.resolve(base, null).definition();
		RunSubmission reset = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(),
				base.configurationJson(), base.datasetDefinition(), base.targetRequest(), base.storageOverrides(),
				base.maximumRecoveryDebt(), base.runtimeCeiling(), base.costCeiling(), true);

		assertThat(resolver.resolve(reset, new CheckpointSeedFacts(source, true)).failures())
			.extracting(RunDefinitionFailure::code)
			.containsExactly("ORDERING_RESET_UNNECESSARY");
	}

	@Test
	void checkpointIndependentFailuresSurviveConfigurationErrors() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunDefinition source = resolver.resolve(base, null).definition();
		RunSubmission invalid = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(),
				"{\"reproducibility\":{\"seed\":\"invalid\"}}",
				new DatasetDefinitionReference("dataset-1", "v2", "sha256:" + "4".repeat(64)), base.targetRequest(),
				base.storageOverrides(), base.maximumRecoveryDebt(), base.runtimeCeiling(), base.costCeiling(), false);

		assertThat(resolver.resolve(invalid, new CheckpointSeedFacts(source, false)).failures())
			.extracting(RunDefinitionFailure::code)
			.contains("CHECKPOINT_CONFIGURATION_INCOMPATIBLE", "ORDERING_RESET_REQUIRED");
	}

	@Test
	void emptyConfigurationDocumentsReturnStableFailures() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunSubmission empty = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(), "  \n\t",
				base.datasetDefinition(), base.targetRequest(), base.storageOverrides(), base.maximumRecoveryDebt(),
				base.runtimeCeiling(), base.costCeiling(), false);

		assertThat(resolver.resolve(empty, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("CONFIG_INVALID_JSON");
		RunSubmission missing = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(), null,
				base.datasetDefinition(), base.targetRequest(), base.storageOverrides(), base.maximumRecoveryDebt(),
				base.runtimeCeiling(), base.costCeiling(), false);
		assertThat(resolver.resolve(missing, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("CONFIG_INVALID_JSON");
	}

	@Test
	void malformedCheckpointDatasetsDoNotProduceOrderingResetAdvice() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunDefinition source = resolver.resolve(base, null).definition();
		RunSubmission malformed = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(),
				base.configurationJson(), new DatasetDefinitionReference("dataset-1", "v2", "malformed"),
				base.targetRequest(), base.storageOverrides(), base.maximumRecoveryDebt(), base.runtimeCeiling(),
				base.costCeiling(), false);

		assertThat(resolver.resolve(malformed, new CheckpointSeedFacts(source, true)).failures())
			.extracting(RunDefinitionFailure::code)
			.containsExactly("DATASET_DEFINITION_INVALID");
		DatasetDefinitionAssessment rejected = new DatasetDefinitionAssessment(false, List
			.of(new RunDefinitionFailure("DATASET_FINGERPRINT_MISMATCH", "dataset", "/datasetDefinition", "const")));
		RunDefinitionResolver rejectingResolver = resolver(eligibleVersionRegistry(), rejected, targets(), storage(),
				"EUR");
		RunSubmission changed = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(),
				base.configurationJson(), new DatasetDefinitionReference("dataset-1", "v2", "sha256:" + "4".repeat(64)),
				base.targetRequest(), base.storageOverrides(), base.maximumRecoveryDebt(), base.runtimeCeiling(),
				base.costCeiling(), false);
		assertThat(rejectingResolver.resolve(changed, new CheckpointSeedFacts(source, true)).failures())
			.extracting(RunDefinitionFailure::code)
			.containsExactly("DATASET_FINGERPRINT_MISMATCH");
	}

	@Test
	void missingDatasetsDoNotSuppressOtherCheckpointFailures() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunDefinition source = resolver.resolve(base, null).definition();
		RunSubmission missingDataset = new RunSubmission(base.trainingProject(), base.manifestArtifactDigest(),
				"{\"reproducibility\":{\"seed\":10}}", null, base.targetRequest(), base.storageOverrides(),
				base.maximumRecoveryDebt(), base.runtimeCeiling(), base.costCeiling(), false);

		assertThat(resolver.resolve(missingDataset, new CheckpointSeedFacts(source, false)).failures())
			.extracting(RunDefinitionFailure::code)
			.contains("DATASET_DEFINITION_INVALID", "CHECKPOINT_ORDERING_SEED_CHANGED",
					"CHECKPOINT_CONFIGURATION_INCOMPATIBLE");
	}

	@Test
	void exactTargetPinsNeverFallBackAndImageMapsMustCoverEligibleBackends() {
		RunDefinitionResolver pinnedResolver = resolver(eligibleVersionRegistry(),
				DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(eligibleTarget("another-target", TargetClass.CLOUD_SPOT, "cuda",
						"H100", 8, 80L * 1024 * 1024 * 1024)), List.of()),
				storage(), "EUR");
		RunSubmission pinned = withTarget(submission(TargetClass.CLOUD_SPOT),
				new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, "pinned-target", null, null));
		RunDefinitionResolver incompatibleResolver = resolver(eligibleVersionRegistry(),
				DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(
						List.of(eligibleTarget("tpu-target", TargetClass.CLOUD_SPOT, "tpu", "TPU", 8, Long.MAX_VALUE)),
						List.of()),
				storage(), "EUR");

		assertThat(pinnedResolver.resolve(pinned, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("TARGET_UNSUPPORTED");
		assertThat(incompatibleResolver
			.resolve(withTarget(submission(TargetClass.CLOUD_SPOT),
					new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, null, "TPU", null)), null)
			.failures()).extracting(RunDefinitionFailure::code).containsExactly("PROJECT_CAPABILITIES_INCOMPATIBLE");
	}

	@Test
	void derivesUniqueModelEvidenceAndDefersAmbiguousAcceleratorChoices() {
		TargetEligibilityAssessment oneCandidate = new TargetEligibilityAssessment(List
			.of(eligibleTarget("pinned-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8, 80L * 1024 * 1024 * 1024)),
				List.of());
		RunDefinitionResolver resolved = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				oneCandidate, storage(), "EUR");
		RunSubmission omittedModel = withTarget(submission(TargetClass.CLOUD_SPOT),
				new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, "pinned-target", null, null));
		RunDefinitionResolver ambiguous = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");

		JsonNode target = resolved.resolve(omittedModel, null).definition().value().path("targetRequest");
		assertThat(target.path("purchaseMode").asText()).isEqualTo("spot");
		assertThat(target.path("gpuModel").asText()).isEqualTo("H100");
		JsonNode deferred = ambiguous
			.resolve(withTarget(submission(TargetClass.CLOUD_SPOT),
					new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, null, null, null)), null)
			.definition()
			.value()
			.path("targetRequest");
		assertThat(deferred.has("acceleratorBackend")).isFalse();
		assertThat(deferred.has("gpuModel")).isFalse();
	}

	@Test
	void runtimeAndCostCeilingsRemainIndependentAndOptional() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunSubmission absent = withCeilings(base, null, null);
		RunSubmission runtimeOnly = withCeilings(base, Duration.ofNanos(1), null);
		RunSubmission costOnly = withCeilings(base, null, new BigDecimal("1.25"));

		JsonNode absentPolicy = resolver.resolve(absent, null).definition().value().path("executionPolicy");
		JsonNode runtimePolicy = resolver.resolve(runtimeOnly, null).definition().value().path("executionPolicy");
		JsonNode costPolicy = resolver.resolve(costOnly, null).definition().value().path("executionPolicy");
		assertThat(absentPolicy.has("runtimeCeiling")).isFalse();
		assertThat(absentPolicy.has("costCeiling")).isFalse();
		assertThat(runtimePolicy.path("runtimeCeiling").asText()).isEqualTo("PT0.000000001S");
		assertThat(runtimePolicy.has("costCeiling")).isFalse();
		assertThat(costPolicy.has("runtimeCeiling")).isFalse();
		assertThat(costPolicy.at("/costCeiling/currency").asText()).isEqualTo("EUR");
	}

	@Test
	void nonPortableDecimalsReturnStableResolutionFailures() {
		RunDefinitionResolver resolver = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");
		RunSubmission base = submission(TargetClass.CLOUD_SPOT);
		RunSubmission cost = withCeilings(base, base.runtimeCeiling(), new BigDecimal(BigInteger.ONE, 1_000_000_001));

		assertThat(resolver.resolve(cost, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("COST_CEILING_INVALID");
		RunSubmission overlong = withCeilings(base, base.runtimeCeiling(), new BigDecimal("1".repeat(4_001)));
		assertThat(resolver.resolve(overlong, null).failures()).extracting(RunDefinitionFailure::code)
			.containsExactly("COST_CEILING_INVALID");
	}

	@Test
	void unavailableDependenciesRemainDistinctFromInvalidInput() {
		RunDefinitionResolver resolver = new RunDefinitionResolver(new TrainingProjectVersions(
				eligibleVersionRegistry(), this.configurationContracts, this.metricContracts), ignored -> {
					throw new IllegalStateException("offline");
				}, () -> {
					throw new IllegalStateException("offline");
				}, (targetClass, overrides) -> {
					throw new IllegalStateException("TARGET_STORAGE_INELIGIBLE: inactive");
				}, () -> {
					throw new IllegalStateException("offline");
				}, (request, currency, quoteTime) -> {
					throw new IllegalStateException("offline");
				});

		assertThat(resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).failures())
			.extracting(RunDefinitionFailure::code)
			.contains("DATASET_DEPENDENCY_UNAVAILABLE", "TARGET_ELIGIBILITY_UNAVAILABLE", "TARGET_STORAGE_UNAVAILABLE",
					"REPORTING_CURRENCY_UNAVAILABLE");
	}

	@Test
	void preservesStableTargetStorageDomainCodes() {
		RunDefinitionResolver resolver = new RunDefinitionResolver(
				new TrainingProjectVersions(eligibleVersionRegistry(), this.configurationContracts,
						this.metricContracts),
				ignored -> DatasetDefinitionAssessment.accepted(), () -> targets(), (targetClass, overrides) -> {
					throw new RunDefinitionStorageException("TARGET_STORAGE_INELIGIBLE", "inactive", null);
				}, () -> "EUR", quoteReader(targets()));

		assertThat(resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).failures())
			.extracting(RunDefinitionFailure::code)
			.containsExactly("TARGET_STORAGE_INELIGIBLE");
	}

	private RunDefinitionResolver resolver(ProjectVersionRegistry registry, DatasetDefinitionAssessment dataset,
			TargetEligibilityAssessment eligibility, RunDefinitionStorageSelection storage, String currency) {
		return resolver(registry, dataset, eligibility, storage, currency, Clock.systemUTC());
	}

	private RunDefinitionResolver resolver(ProjectVersionRegistry registry, DatasetDefinitionAssessment dataset,
			TargetEligibilityAssessment eligibility, RunDefinitionStorageSelection storage, String currency,
			Clock clock) {
		return resolver(registry, dataset, eligibility, storage, currency, clock, quoteReader(eligibility));
	}

	private RunDefinitionResolver resolver(ProjectVersionRegistry registry, DatasetDefinitionAssessment dataset,
			TargetEligibilityAssessment eligibility, RunDefinitionStorageSelection storage, String currency,
			Clock clock, CostQuoteReader quoteReader) {
		return new RunDefinitionResolver(
				new TrainingProjectVersions(registry, this.configurationContracts, this.metricContracts),
				ignored -> dataset, () -> eligibility, (targetClass, overrides) -> storage, () -> currency, quoteReader,
				clock);
	}

	private RunSubmission submission(TargetClass targetClass) {
		return new RunSubmission(new TrainingProjectBinding("stable-project", "registry"), VERSION_DIGEST,
				"{\"reproducibility\":{\"seed\":9}}", dataset(),
				new TargetRequest(targetClass, targetClass == TargetClass.LOCAL_SINGLE_GPU ? 1 : 2,
						80L * 1024 * 1024 * 1024, null, "H100", null),
				RunDefinitionStorageOverrides.none(), null, Duration.ofHours(1), new BigDecimal("12.3400"), false);
	}

	private static RunSubmission withTarget(RunSubmission source, TargetRequest target) {
		return new RunSubmission(source.trainingProject(), source.manifestArtifactDigest(), source.configurationJson(),
				source.datasetDefinition(), target, source.storageOverrides(), source.maximumRecoveryDebt(),
				source.runtimeCeiling(), source.costCeiling(), source.orderingReset());
	}

	private static RunSubmission withCeilings(RunSubmission source, Duration runtime, BigDecimal cost) {
		return new RunSubmission(source.trainingProject(), source.manifestArtifactDigest(), source.configurationJson(),
				source.datasetDefinition(), source.targetRequest(), source.storageOverrides(),
				source.maximumRecoveryDebt(), runtime, cost, source.orderingReset());
	}

	private static DatasetDefinitionReference dataset() {
		return new DatasetDefinitionReference("dataset-1", "v1", "sha256:" + "2".repeat(64));
	}

	private static TargetEligibilityAssessment targets() {
		List<EligibleTarget> targets = new ArrayList<>();
		for (TargetClass targetClass : TargetClass.values()) {
			targets
				.add(eligibleTarget("target-" + targetClass, targetClass, "cuda", "H100", 8, 80L * 1024 * 1024 * 1024));
			targets.add(
					eligibleTarget("rocm-" + targetClass, targetClass, "rocm", "MI300X", 8, 192L * 1024 * 1024 * 1024));
		}
		return new TargetEligibilityAssessment(targets, List.of());
	}

	private static EligibleTarget eligibleTarget(String target, TargetClass targetClass, String acceleratorBackend,
			String gpuModel, int maximumGpuCount, long gpuMemoryBytes) {
		return new EligibleTarget(target, targetClass, acceleratorBackend, gpuModel, maximumGpuCount, gpuMemoryBytes);
	}

	private static CostQuoteReader quoteReader(TargetEligibilityAssessment eligibility) {
		return (request, currency,
				quoteTime) -> new CostQuoteAssessment(eligibility.targets()
					.stream()
					.filter(target -> target.targetClass() == request.targetClass())
					.filter(target -> request.target() == null || request.target().equals(target.target()))
					.filter(target -> request.gpuModel() == null || request.gpuModel().equals(target.gpuModel()))
					.map(target -> quoteCandidate(target, quoteTime))
					.toList(), List.of());
	}

	private static CostQuoteCandidate quoteCandidate(EligibleTarget target, Instant quoteTime) {
		UUID source = UUID.fromString("00000000-0000-0000-0000-000000000100");
		return new CostQuoteCandidate(UUID.fromString("00000000-0000-0000-0000-000000000200"), 1, target.targetClass(),
				target.target(), "provider-offering", "test-region", "test-instance", target.gpuModel(),
				target.maximumGpuCount(), target.gpuMemoryBytes(), purchaseMode(target.targetClass()), "first-class",
				new BigDecimal("2.5000"), "EUR", "instance-hour", BigDecimal.ONE, BigDecimal.ONE,
				java.util.Collections.singletonMap("note", null), source, 3, "operator-schedule",
				Instant.parse("2025-01-01T00:00:00Z"), null, quoteTime.minusSeconds(1), quoteTime.minusSeconds(2),
				quoteTime.minusSeconds(1), Duration.ofHours(24), null);
	}

	private static CostQuoteCandidate withBillingRules(CostQuoteCandidate source, BigDecimal minimumQuantity,
			BigDecimal billingQuantum) {
		return new CostQuoteCandidate(source.offeringId(), source.offeringRevision(), source.targetClass(),
				source.target(), source.providerOfferingId(), source.region(), source.instanceType(), source.gpuModel(),
				source.gpuCount(), source.gpuMemoryBytes(), source.purchaseMode(), source.supportTier(),
				source.nativeRate(), source.nativeCurrency(), source.nativeUnit(), minimumQuantity, billingQuantum,
				source.provenance(), source.sourceId(), source.sourceRevision(), source.sourceKind(),
				source.effectiveFrom(), source.effectiveUntil(), source.rateObservedAt(), source.sourceObservedFrom(),
				source.sourceObservedUntil(), source.maximumObservationAge(), source.conversion());
	}

	private static CostQuoteCandidate withEffectiveInterval(CostQuoteCandidate source, Instant effectiveFrom,
			Instant effectiveUntil) {
		return new CostQuoteCandidate(source.offeringId(), source.offeringRevision(), source.targetClass(),
				source.target(), source.providerOfferingId(), source.region(), source.instanceType(), source.gpuModel(),
				source.gpuCount(), source.gpuMemoryBytes(), source.purchaseMode(), source.supportTier(),
				source.nativeRate(), source.nativeCurrency(), source.nativeUnit(), source.minimumQuantity(),
				source.billingQuantum(), source.provenance(), source.sourceId(), source.sourceRevision(),
				source.sourceKind(), effectiveFrom, effectiveUntil, source.rateObservedAt(),
				source.sourceObservedFrom(), source.sourceObservedUntil(), source.maximumObservationAge(),
				source.conversion());
	}

	private static CostQuoteCandidate withRate(CostQuoteCandidate source, BigDecimal rate) {
		return withCurrencyAndRate(source, source.nativeCurrency(), rate);
	}

	private static CostQuoteCandidate withCurrencyAndRate(CostQuoteCandidate source, String currency, BigDecimal rate) {
		return new CostQuoteCandidate(source.offeringId(), source.offeringRevision(), source.targetClass(),
				source.target(), source.providerOfferingId(), source.region(), source.instanceType(), source.gpuModel(),
				source.gpuCount(), source.gpuMemoryBytes(), source.purchaseMode(), source.supportTier(), rate, currency,
				source.nativeUnit(), source.minimumQuantity(), source.billingQuantum(), source.provenance(),
				source.sourceId(), source.sourceRevision(), source.sourceKind(), source.effectiveFrom(),
				source.effectiveUntil(), source.rateObservedAt(), source.sourceObservedFrom(),
				source.sourceObservedUntil(), source.maximumObservationAge(), source.conversion());
	}

	private static CostQuoteCandidate withConversion(CostQuoteCandidate source, CostQuoteConversion conversion) {
		return new CostQuoteCandidate(source.offeringId(), source.offeringRevision(), source.targetClass(),
				source.target(), source.providerOfferingId(), source.region(), source.instanceType(), source.gpuModel(),
				source.gpuCount(), source.gpuMemoryBytes(), source.purchaseMode(), source.supportTier(),
				source.nativeRate(), source.nativeCurrency(), source.nativeUnit(), source.minimumQuantity(),
				source.billingQuantum(), source.provenance(), source.sourceId(), source.sourceRevision(),
				source.sourceKind(), source.effectiveFrom(), source.effectiveUntil(), source.rateObservedAt(),
				source.sourceObservedFrom(), source.sourceObservedUntil(), source.maximumObservationAge(), conversion);
	}

	private static String purchaseMode(TargetClass targetClass) {
		return switch (targetClass) {
			case LOCAL_SINGLE_GPU, LOCAL_MULTI_GPU -> "local";
			case CLOUD_ON_DEMAND -> "on-demand";
			case CLOUD_SPOT -> "spot";
		};
	}

	private static RunDefinitionStorageSelection storage() {
		return new RunDefinitionStorageSelection(
				new RunDefinitionStorageSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), 7, 3,
						URI.create("https://runs.example"), "runs", "eu-central-1", true,
						Map.of("chunkedEncoding", "disabled")),
				false, new RunDefinitionStorageSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000002"), 4, 2,
						URI.create("https://home.example"), "home", "local", false, Map.of()));
	}

	private ProjectVersionRegistry eligibleVersionRegistry() {
		String configuration = """
				{"contractVersion":1,"skywrightSchema":%s,"projectSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{}},"defaults":{},"defaultsCompletionWitness":{},"references":{}}
				"""
			.formatted(this.configurationContracts.skywrightSchemaIdentityJson())
			.trim();
		String metric = this.metricContracts
			.compile("{\"contractVersion\":1,\"definitions\":[],\"skywrightSchema\":"
					+ this.metricContracts.skywrightSchemaIdentityJson() + "}")
			.canonicalJson();
		String configurationOci = "sha256:" + "e".repeat(64);
		String metricOci = "sha256:" + "f".repeat(64);
		String manifest = """
				{"manifestVersion":1,"projectIdentity":"stable-project","versionLabel":"%s-github-42","sourceRevision":"%s","pipeline":"github-42","acceleratorBackends":["cuda","rocm"],"images":{"cuda":"sha256:%s","rocm":"sha256:%s"},"environmentProfiles":{"cuda":"registry.example/environment:cuda@sha256:%s","rocm":"registry.example/environment:rocm@sha256:%s"},"configurationContract":{"digest":"%s","skywrightSchema":%s},"metricContract":{"digest":"%s","skywrightSchema":%s},"contractArtifacts":{"cuda":{"configuration":"%s","metrics":"%s"},"rocm":{"configuration":"%s","metrics":"%s"}}}
				"""
			.formatted("1".repeat(40), "1".repeat(40), "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
					digest(configuration), this.configurationContracts.skywrightSchemaIdentityJson(), digest(metric),
					this.metricContracts.skywrightSchemaIdentityJson(), configurationOci, metricOci, configurationOci,
					metricOci)
			.trim();
		return new FakeRegistry().artifact(VERSION_DIGEST, manifest)
			.artifact(configurationOci, configuration)
			.artifact(metricOci, metric)
			.image("sha256:" + "a".repeat(64))
			.image("sha256:" + "b".repeat(64))
			.image("sha256:" + "c".repeat(64))
			.image("sha256:" + "d".repeat(64));
	}

	private static String digest(String value) {
		try {
			return "sha256:" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static final class FakeRegistry implements ProjectVersionRegistry {

		private final Map<String, RegistryArtifact> artifacts = new HashMap<>();

		private final Set<String> images = new HashSet<>();

		FakeRegistry artifact(String digest, String content) {
			this.artifacts.put(digest, new RegistryArtifact(digest, content));
			return this;
		}

		FakeRegistry image(String digest) {
			this.images.add(digest);
			return this;
		}

		@Override
		public List<ProjectVersionReference> listVersions(String repository) {
			return List.of();
		}

		@Override
		public Optional<RegistryArtifact> pullArtifact(String repository, String reference) {
			return Optional.ofNullable(this.artifacts.get(reference));
		}

		@Override
		public boolean imageAvailable(String repository, String digest) {
			return this.images.contains(digest);
		}

	}

}
