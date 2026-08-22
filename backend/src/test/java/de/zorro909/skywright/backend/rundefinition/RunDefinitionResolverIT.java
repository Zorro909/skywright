package de.zorro909.skywright.backend.rundefinition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
		assertThat(definition.at("/configuration/reproducibility/seed").asInt()).isEqualTo(9);
		assertThat(definition.at("/configuration/checkpoint/retention").asInt()).isEqualTo(3);
		assertThat(definition.at("/datasetDefinition/datasetIdentity").asText()).isEqualTo("dataset-1");
		assertThat(definition.at("/targetRequest/gpuModel").asText()).isEqualTo("H100");
		assertThat(definition.at("/storage/execution/registrationRevision").asLong()).isEqualTo(7);
		assertThat(definition.at("/storage/repatriation/enabled").asBoolean()).isFalse();
		assertThat(definition.at("/executionPolicy/maximumRecoveryDebt").asInt()).isEqualTo(3);
		assertThat(definition.at("/targetRequest/purchaseMode").asText()).isEqualTo("spot");
		assertThat(definition.at("/targetRequest/acceleratorBackend").asText()).isEqualTo("cuda");
		assertThat(definition.at("/executionPolicy/runtimeCeiling").asText()).isEqualTo("PT1H");
		assertThat(definition.at("/executionPolicy/costCeiling/amount").decimalValue()).isEqualByComparingTo("12.3400");
		assertThat(definition.at("/executionPolicy/costCeiling/currency").asText()).isEqualTo("EUR");
		assertThat(definition.toString()).doesNotContain("credential", "runId", "checkpointReference", "metricCatalog",
				"lifecycle", "orchestrator", "instanceType", "storageLocation", "datasetCopy");
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
			assertThat(resolution.definition().value().at("/targetRequest/acceleratorBackend").asText())
				.isEqualTo("cuda");
		}
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
				}, () -> "EUR");
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
	void exactTargetPinsNeverFallBackAndImageMapsMustCoverEligibleBackends() {
		RunDefinitionResolver pinnedResolver = resolver(eligibleVersionRegistry(),
				DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List.of(new EligibleTarget("another-target", TargetClass.CLOUD_SPOT,
						"cuda", "H100", 8, 80L * 1024 * 1024 * 1024)), List.of()),
				storage(), "EUR");
		RunSubmission pinned = withTarget(submission(TargetClass.CLOUD_SPOT),
				new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, "pinned-target", null, null));
		RunDefinitionResolver incompatibleResolver = resolver(eligibleVersionRegistry(),
				DatasetDefinitionAssessment.accepted(),
				new TargetEligibilityAssessment(List
					.of(new EligibleTarget("tpu-target", TargetClass.CLOUD_SPOT, "tpu", "TPU", 8, Long.MAX_VALUE)),
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
	void derivesTargetFactsAndRejectsAmbiguousEligibleEvidence() {
		TargetEligibilityAssessment oneCandidate = new TargetEligibilityAssessment(List.of(new EligibleTarget(
				"pinned-target", TargetClass.CLOUD_SPOT, "cuda", "H100", 8, 80L * 1024 * 1024 * 1024)), List.of());
		RunDefinitionResolver resolved = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				oneCandidate, storage(), "EUR");
		RunSubmission omittedModel = withTarget(submission(TargetClass.CLOUD_SPOT),
				new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, "pinned-target", null, null));
		RunDefinitionResolver ambiguous = resolver(eligibleVersionRegistry(), DatasetDefinitionAssessment.accepted(),
				targets(), storage(), "EUR");

		JsonNode target = resolved.resolve(omittedModel, null).definition().value().path("targetRequest");
		assertThat(target.path("purchaseMode").asText()).isEqualTo("spot");
		assertThat(target.path("acceleratorBackend").asText()).isEqualTo("cuda");
		assertThat(target.path("gpuModel").asText()).isEqualTo("H100");
		assertThat(ambiguous
			.resolve(withTarget(submission(TargetClass.CLOUD_SPOT),
					new TargetRequest(TargetClass.CLOUD_SPOT, 1, null, null, null, null)), null)
			.failures()).extracting(RunDefinitionFailure::code).containsExactly("TARGET_EVIDENCE_AMBIGUOUS");
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
				}, () -> "EUR");

		assertThat(resolver.resolve(submission(TargetClass.CLOUD_SPOT), null).failures())
			.extracting(RunDefinitionFailure::code)
			.containsExactly("TARGET_STORAGE_INELIGIBLE");
	}

	private RunDefinitionResolver resolver(ProjectVersionRegistry registry, DatasetDefinitionAssessment dataset,
			TargetEligibilityAssessment eligibility, RunDefinitionStorageSelection storage, String currency) {
		return new RunDefinitionResolver(
				new TrainingProjectVersions(registry, this.configurationContracts, this.metricContracts),
				ignored -> dataset, () -> eligibility, (targetClass, overrides) -> storage, () -> currency);
	}

	private RunSubmission submission(TargetClass targetClass) {
		return new RunSubmission(new TrainingProjectBinding("stable-project", "registry"), VERSION_DIGEST,
				"{\"reproducibility\":{\"seed\":9}}", dataset(),
				new TargetRequest(targetClass, 2, 80L * 1024 * 1024 * 1024, null, "H100", null),
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
			targets.add(new EligibleTarget("target-" + targetClass, targetClass, "cuda", "H100", 8,
					80L * 1024 * 1024 * 1024));
			targets.add(new EligibleTarget("rocm-" + targetClass, targetClass, "rocm", "MI300X", 8,
					192L * 1024 * 1024 * 1024));
		}
		return new TargetEligibilityAssessment(targets, List.of());
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
