package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import de.zorro909.skywright.backend.configurationcontract.ConfigurationContractException;
import de.zorro909.skywright.backend.projectversion.ProjectVersionAssessment;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersion;
import de.zorro909.skywright.backend.projectversion.TrainingProjectVersions;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSelection;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageSnapshot;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Resolves Run Submission intent into one complete, immutable, write-free artifact. */
public final class RunDefinitionResolver {

	private static final int DEFAULT_MAXIMUM_RECOVERY_DEBT = 3;

	private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

	private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TrainingProjectVersions projectVersions;

	private final DatasetDefinitionReader datasets;

	private final TargetEligibilityReader targetEligibility;

	private final RunDefinitionStorageReader targetStorages;

	private final ReportingCurrencyReader reportingCurrency;

	public RunDefinitionResolver(TrainingProjectVersions projectVersions, DatasetDefinitionReader datasets,
			TargetEligibilityReader targetEligibility, RunDefinitionStorageReader targetStorages,
			ReportingCurrencyReader reportingCurrency) {
		this.projectVersions = projectVersions;
		this.datasets = datasets;
		this.targetEligibility = targetEligibility;
		this.targetStorages = targetStorages;
		this.reportingCurrency = reportingCurrency;
	}

	public RunDefinitionResolution resolve(RunSubmission submission, CheckpointSeedFacts checkpointSeed) {
		List<RunDefinitionFailure> failures = new ArrayList<>();
		validateSubmission(submission, checkpointSeed, failures);

		TrainingProjectVersion version = resolveVersion(submission, failures);
		JsonNode configuration = resolveConfiguration(submission, version, failures);
		assessDataset(submission.datasetDefinition(), failures);
		ResolvedTarget target = resolveTarget(submission.targetRequest(), version, failures);
		RunDefinitionStorageSelection storage = resolveStorage(submission, failures);
		String currency = resolveCurrency(submission.costCeiling(), failures);
		assessCheckpoint(submission, checkpointSeed, version, configuration, failures);

		List<RunDefinitionFailure> ordered = failures.stream().distinct().sorted().toList();
		if (!ordered.isEmpty()) {
			return new RunDefinitionResolution(null, ordered);
		}
		try {
			return new RunDefinitionResolution(build(submission, version, configuration, target, storage, currency),
					List.of());
		}
		catch (RunDefinitionValidationException error) {
			return new RunDefinitionResolution(null, error.failures().stream().distinct().sorted().toList());
		}
	}

	private static void validateSubmission(RunSubmission submission, CheckpointSeedFacts checkpointSeed,
			List<RunDefinitionFailure> failures) {
		TargetRequest target = submission.targetRequest();
		if (target == null || target.targetClass() == null) {
			failures.add(failure("TARGET_CLASS_INVALID", "submission", "/targetRequest/targetClass", "enum"));
		}
		if (target == null || target.gpuCount() <= 0) {
			failures.add(failure("GPU_COUNT_INVALID", "submission", "/targetRequest/gpuCount", "minimum"));
		}
		if (target != null && target.minimumGpuMemoryBytes() != null && target.minimumGpuMemoryBytes() <= 0) {
			failures
				.add(failure("GPU_MEMORY_INVALID", "submission", "/targetRequest/minimumGpuMemoryBytes", "minimum"));
		}
		if (target != null && target.minimumThroughput() != null) {
			failures.add(failure("MINIMUM_THROUGHPUT_UNSUPPORTED", "submission", "/targetRequest/minimumThroughput",
					"unsupported"));
		}
		Integer maximumDebt = submission.maximumRecoveryDebt();
		if (maximumDebt != null && maximumDebt <= 0) {
			failures.add(failure("MAXIMUM_RECOVERY_DEBT_INVALID", "submission", "/maximumRecoveryDebt", "minimum"));
		}
		Duration runtime = submission.runtimeCeiling();
		if (runtime != null && (runtime.isNegative() || runtime.isZero())) {
			failures.add(failure("RUNTIME_CEILING_INVALID", "submission", "/runtimeCeiling", "minimum"));
		}
		if (submission.costCeiling() != null && (submission.costCeiling().signum() <= 0
				|| !RunDefinitionCodec.hasPortableDecimal(submission.costCeiling()))) {
			failures.add(failure("COST_CEILING_INVALID", "submission", "/costCeiling/amount",
					submission.costCeiling().signum() <= 0 ? "exclusiveMinimum" : "portableDecimal"));
		}
		if (submission.orderingReset() && checkpointSeed == null) {
			failures.add(failure("ORDERING_RESET_REQUIRES_CHECKPOINT", "submission", "/orderingReset", "requires"));
		}
	}

	private TrainingProjectVersion resolveVersion(RunSubmission submission, List<RunDefinitionFailure> failures) {
		if (submission.trainingProject() == null || submission.manifestArtifactDigest() == null) {
			failures.add(failure("PROJECT_VERSION_MISSING", "training-project-version", "/trainingProjectVersion",
					"required"));
			return null;
		}
		ProjectVersionAssessment assessment = this.projectVersions.discover(submission.trainingProject(),
				submission.manifestArtifactDigest());
		if (!assessment.runnable()) {
			assessment.errors()
				.forEach(error -> failures.add(failure(error.code(), "training-project-version", error.pointer(), "")));
			return null;
		}
		return assessment.version();
	}

	private static JsonNode resolveConfiguration(RunSubmission submission, TrainingProjectVersion version,
			List<RunDefinitionFailure> failures) {
		if (version == null) {
			return null;
		}
		try {
			return version.configurationContract().resolve(submission.configurationJson());
		}
		catch (ConfigurationContractException error) {
			error.errors()
				.forEach(item -> failures.add(failure(item.code(), item.source(), item.pointer(), item.keyword())));
			return null;
		}
	}

	private void assessDataset(DatasetDefinitionReference reference, List<RunDefinitionFailure> failures) {
		if (reference == null || blank(reference.datasetIdentity()) || blank(reference.version())
				|| reference.contentFingerprint() == null
				|| !DIGEST.matcher(reference.contentFingerprint()).matches()) {
			failures.add(failure("DATASET_DEFINITION_INVALID", "submission", "/datasetDefinition", "required"));
			return;
		}
		try {
			DatasetDefinitionAssessment assessment = this.datasets.assess(reference);
			if (!assessment.available() || !assessment.failures().isEmpty()) {
				failures.addAll(assessment.failures().isEmpty()
						? List
							.of(failure("DATASET_DEFINITION_UNAVAILABLE", "dataset", "/datasetDefinition", "available"))
						: assessment.failures());
			}
		}
		catch (RuntimeException error) {
			failures.add(failure("DATASET_DEPENDENCY_UNAVAILABLE", "dataset", "/datasetDefinition", "available"));
		}
	}

	private ResolvedTarget resolveTarget(TargetRequest request, TrainingProjectVersion version,
			List<RunDefinitionFailure> failures) {
		if (request == null || request.targetClass() == null || request.gpuCount() <= 0) {
			return null;
		}
		TargetEligibilityAssessment assessment;
		try {
			assessment = this.targetEligibility.assess();
		}
		catch (RuntimeException error) {
			failures
				.add(failure("TARGET_ELIGIBILITY_UNAVAILABLE", "target-eligibility", "/targetRequest", "available"));
			return null;
		}
		if (!assessment.failures().isEmpty()) {
			failures.addAll(assessment.failures());
			return null;
		}
		List<EligibleTarget> matching = assessment.targets()
			.stream()
			.filter(candidate -> candidate.targetClass().equals(request.targetClass()))
			.filter(candidate -> candidate.maximumGpuCount() >= request.gpuCount())
			.filter(candidate -> request.minimumGpuMemoryBytes() == null
					|| candidate.gpuMemoryBytes() >= request.minimumGpuMemoryBytes())
			.filter(candidate -> request.target() == null || candidate.target().equals(request.target()))
			.filter(candidate -> request.gpuModel() == null || candidate.gpuModel().equals(request.gpuModel()))
			.toList();
		if (matching.isEmpty()) {
			failures.add(failure("TARGET_UNSUPPORTED", "target-eligibility", "/targetRequest", "eligible"));
			return null;
		}
		if (version == null) {
			return null;
		}
		List<TargetEvidence> compatible = matching.stream()
			.filter(candidate -> version.images().containsKey(candidate.acceleratorBackend()))
			.map(candidate -> new TargetEvidence(candidate.acceleratorBackend(), candidate.gpuModel()))
			.distinct()
			.toList();
		if (compatible.isEmpty()) {
			failures.add(failure("PROJECT_CAPABILITIES_INCOMPATIBLE", "training-project-version", "/targetRequest",
					"acceleratorBackend"));
			return null;
		}
		if (compatible.size() > 1) {
			failures.add(
					failure("TARGET_EVIDENCE_AMBIGUOUS", "target-eligibility", "/targetRequest", "acceleratorBackend"));
			return null;
		}
		TargetEvidence evidence = compatible.getFirst();
		return new ResolvedTarget(request, purchaseMode(request.targetClass()), evidence.acceleratorBackend(),
				evidence.gpuModel());
	}

	private RunDefinitionStorageSelection resolveStorage(RunSubmission submission,
			List<RunDefinitionFailure> failures) {
		if (submission.targetRequest() == null || submission.targetRequest().targetClass() == null) {
			return null;
		}
		try {
			RunDefinitionStorageOverrides overrides = submission.storageOverrides() == null
					? RunDefinitionStorageOverrides.none() : submission.storageOverrides();
			return this.targetStorages.resolve(submission.targetRequest().targetClass(), overrides);
		}
		catch (RunDefinitionStorageException error) {
			failures.add(failure(error.code(), "target-storage", "/storage", "eligible"));
			return null;
		}
		catch (RuntimeException error) {
			failures.add(failure("TARGET_STORAGE_UNAVAILABLE", "target-storage", "/storage", "available"));
			return null;
		}
	}

	private String resolveCurrency(BigDecimal ceiling, List<RunDefinitionFailure> failures) {
		if (ceiling == null || ceiling.signum() <= 0) {
			return null;
		}
		try {
			String currency = this.reportingCurrency.reportingCurrency();
			if (currency == null || !CURRENCY.matcher(currency).matches()) {
				failures.add(failure("REPORTING_CURRENCY_INVALID", "reporting-currency", "/costCeiling/currency",
						"pattern"));
				return null;
			}
			return currency;
		}
		catch (RuntimeException error) {
			failures.add(failure("REPORTING_CURRENCY_UNAVAILABLE", "reporting-currency", "/costCeiling/currency",
					"available"));
			return null;
		}
	}

	private static void assessCheckpoint(RunSubmission submission, CheckpointSeedFacts seed,
			TrainingProjectVersion version, JsonNode configuration, List<RunDefinitionFailure> failures) {
		if (seed == null) {
			return;
		}
		RunDefinition source = seed.sourceDefinition();
		if (version != null && !source.manifestArtifactDigest().equals(version.manifestArtifactDigest())) {
			failures.add(failure("CHECKPOINT_PROJECT_VERSION_CHANGED", "checkpoint-seed", "/trainingProjectVersion",
					"const"));
		}
		if (configuration != null) {
			JsonNode sourceConfiguration = source.configuration();
			if (!sourceConfiguration.at("/reproducibility/seed").equals(configuration.at("/reproducibility/seed"))) {
				failures.add(failure("CHECKPOINT_ORDERING_SEED_CHANGED", "checkpoint-seed",
						"/configuration/reproducibility/seed", "const"));
			}
			if (!sourceConfiguration.at("/dataset/ordering/policy")
				.equals(configuration.at("/dataset/ordering/policy"))) {
				failures.add(failure("CHECKPOINT_ORDERING_POLICY_CHANGED", "checkpoint-seed",
						"/configuration/dataset/ordering/policy", "const"));
			}
		}
		if (!seed.libraryConfigurationCompatible()) {
			failures.add(failure("CHECKPOINT_CONFIGURATION_INCOMPATIBLE", "checkpoint-seed", "/configuration",
					"resumeCompatible"));
		}
		if (submission.datasetDefinition() != null) {
			boolean datasetChanged = !source.datasetDefinition().equals(dataset(submission.datasetDefinition()));
			if (datasetChanged && !submission.orderingReset()) {
				failures.add(failure("ORDERING_RESET_REQUIRED", "checkpoint-seed", "/orderingReset", "required"));
			}
			else if (!datasetChanged && submission.orderingReset()) {
				failures.add(failure("ORDERING_RESET_UNNECESSARY", "checkpoint-seed", "/orderingReset", "const"));
			}
		}
	}

	private static RunDefinition build(RunSubmission submission, TrainingProjectVersion version, JsonNode configuration,
			ResolvedTarget target, RunDefinitionStorageSelection storage, String currency) {
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", 1);
		root.set("trainingProjectVersion", projectVersion(version));
		root.set("configuration", configuration.deepCopy());
		root.set("datasetDefinition", dataset(submission.datasetDefinition()));
		root.set("targetRequest", target(target));
		root.set("storage", storage(storage));
		root.set("executionPolicy", executionPolicy(submission, currency));
		root.put("orderingReset", submission.orderingReset());
		return RunDefinition.from(root);
	}

	private static ObjectNode projectVersion(TrainingProjectVersion version) {
		ObjectNode result = JSON.createObjectNode();
		result.put("projectIdentity", version.projectIdentity());
		result.put("versionLabel", version.versionLabel());
		result.put("manifestArtifactDigest", version.manifestArtifactDigest());
		result.put("sourceRevision", version.sourceRevision());
		result.put("pipeline", version.pipeline());
		ObjectNode images = result.putObject("images");
		new TreeMap<>(version.images()).forEach(images::put);
		ObjectNode configuration = result.putObject("configurationContract");
		configuration.put("digest", version.configurationContractDigest());
		try {
			configuration.set("skywrightSchema",
					JSON.readTree(version.configurationContract().skywrightSchemaIdentityJson()));
		}
		catch (JacksonException error) {
			throw new IllegalStateException("verified configuration identity cannot be decoded", error);
		}
		ObjectNode metrics = result.putObject("metricContract");
		metrics.put("digest", version.metricContractDigest());
		metrics.putObject("skywrightSchema")
			.put("version", version.metricCatalog().skywrightSchema().version())
			.put("digest", version.metricCatalog().skywrightSchema().digest());
		return result;
	}

	private static ObjectNode dataset(DatasetDefinitionReference reference) {
		return JSON.createObjectNode()
			.put("datasetIdentity", reference.datasetIdentity())
			.put("version", reference.version())
			.put("contentFingerprint", reference.contentFingerprint());
	}

	private static ObjectNode target(ResolvedTarget resolved) {
		TargetRequest request = resolved.request();
		ObjectNode result = JSON.createObjectNode()
			.put("targetClass", request.targetClass().wireValue())
			.put("purchaseMode", resolved.purchaseMode())
			.put("acceleratorBackend", resolved.acceleratorBackend())
			.put("gpuCount", request.gpuCount());
		if (request.minimumGpuMemoryBytes() != null) {
			result.put("minimumGpuMemoryBytes", request.minimumGpuMemoryBytes());
		}
		if (request.target() != null) {
			result.put("target", request.target());
		}
		result.put("gpuModel", resolved.gpuModel());
		return result;
	}

	private static ObjectNode storage(RunDefinitionStorageSelection selection) {
		ObjectNode result = JSON.createObjectNode();
		result.set("execution", storageSnapshot(selection.execution()));
		ObjectNode repatriation = result.putObject("repatriation");
		repatriation.put("enabled", selection.repatriationEnabled());
		repatriation.set("destination", storageSnapshot(selection.repatriationDestination()));
		return result;
	}

	private static ObjectNode storageSnapshot(RunDefinitionStorageSnapshot snapshot) {
		ObjectNode result = JSON.createObjectNode()
			.put("storageId", snapshot.storageId().toString())
			.put("registrationRevision", snapshot.registrationRevision())
			.put("configurationRevision", snapshot.configurationRevision())
			.put("endpoint", snapshot.endpoint().toString())
			.put("bucket", snapshot.bucket())
			.put("region", snapshot.region())
			.put("addressingMode", snapshot.pathStyleAccess() ? "path" : "virtual-hosted");
		ObjectNode options = result.putObject("compatibilityOptions");
		new TreeMap<>(snapshot.compatibilityOptions()).forEach(options::put);
		return result;
	}

	private static ObjectNode executionPolicy(RunSubmission submission, String currency) {
		ObjectNode result = JSON.createObjectNode()
			.put("maximumRecoveryDebt", submission.maximumRecoveryDebt() == null ? DEFAULT_MAXIMUM_RECOVERY_DEBT
					: submission.maximumRecoveryDebt());
		if (submission.runtimeCeiling() != null) {
			result.put("runtimeCeiling", submission.runtimeCeiling().toString());
		}
		if (submission.costCeiling() != null) {
			result.putObject("costCeiling").put("amount", submission.costCeiling()).put("currency", currency);
		}
		return result;
	}

	private static RunDefinitionFailure failure(String code, String source, String pointer, String keyword) {
		return new RunDefinitionFailure(code, source, pointer, keyword, Map.of());
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String purchaseMode(TargetClass targetClass) {
		return switch (targetClass) {
			case LOCAL_SINGLE_GPU, LOCAL_MULTI_GPU -> "local";
			case CLOUD_ON_DEMAND -> "on-demand";
			case CLOUD_SPOT -> "spot";
		};
	}

	private record TargetEvidence(String acceleratorBackend, String gpuModel) {
	}

	private record ResolvedTarget(TargetRequest request, String purchaseMode, String acceleratorBackend,
			String gpuModel) {
	}

}
