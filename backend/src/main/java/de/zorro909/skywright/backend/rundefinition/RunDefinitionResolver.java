package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	private final Clock clock;

	public RunDefinitionResolver(TrainingProjectVersions projectVersions, DatasetDefinitionReader datasets,
			TargetEligibilityReader targetEligibility, RunDefinitionStorageReader targetStorages,
			ReportingCurrencyReader reportingCurrency) {
		this.projectVersions = projectVersions;
		this.datasets = datasets;
		this.targetEligibility = targetEligibility;
		this.targetStorages = targetStorages;
		this.reportingCurrency = reportingCurrency;
		this.clock = Clock.systemUTC();
	}

	RunDefinitionResolver(TrainingProjectVersions projectVersions, DatasetDefinitionReader datasets,
			TargetEligibilityReader targetEligibility, RunDefinitionStorageReader targetStorages,
			ReportingCurrencyReader reportingCurrency, Clock clock) {
		this.projectVersions = projectVersions;
		this.datasets = datasets;
		this.targetEligibility = targetEligibility;
		this.targetStorages = targetStorages;
		this.reportingCurrency = reportingCurrency;
		this.clock = clock;
	}

	public RunDefinitionResolution resolve(RunSubmission submission, CheckpointSeedFacts checkpointSeed) {
		List<RunDefinitionFailure> failures = new ArrayList<>();
		validateSubmission(submission, checkpointSeed, failures);

		TrainingProjectVersion version = resolveVersion(submission, failures);
		JsonNode configuration = resolveConfiguration(submission, version, failures);
		boolean datasetReferenceValid = assessDataset(submission.datasetDefinition(), failures);
		ResolvedTarget target = resolveTarget(submission.targetRequest(), version, failures);
		RunDefinitionStorageSelection storage = resolveStorage(submission, failures);
		String currency = resolveCurrency(failures);
		Instant quoteTime = this.clock.instant();
		assessPrices(target, currency, quoteTime, failures);
		assessCheckpoint(submission, checkpointSeed, version, configuration, datasetReferenceValid, failures);

		List<RunDefinitionFailure> ordered = failures.stream().distinct().sorted().toList();
		if (!ordered.isEmpty()) {
			return new RunDefinitionResolution(null, ordered);
		}
		try {
			return new RunDefinitionResolution(
					build(submission, version, configuration, target, storage, currency, quoteTime), List.of());
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
		if (target == null || !validGpuCount(target)) {
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
		if (submission.configurationJson() == null) {
			failures.add(failure("CONFIG_INVALID_JSON", "configuration", "", "parse"));
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

	private boolean assessDataset(DatasetDefinitionReference reference, List<RunDefinitionFailure> failures) {
		if (reference == null || blank(reference.datasetIdentity()) || blank(reference.version())
				|| reference.contentFingerprint() == null
				|| !DIGEST.matcher(reference.contentFingerprint()).matches()) {
			failures.add(failure("DATASET_DEFINITION_INVALID", "submission", "/datasetDefinition", "required"));
			return false;
		}
		try {
			DatasetDefinitionAssessment assessment = this.datasets.assess(reference);
			if (!assessment.available() || !assessment.failures().isEmpty()) {
				failures.addAll(assessment.failures().isEmpty()
						? List
							.of(failure("DATASET_DEFINITION_UNAVAILABLE", "dataset", "/datasetDefinition", "available"))
						: assessment.failures());
				return false;
			}
		}
		catch (RuntimeException error) {
			failures.add(failure("DATASET_DEPENDENCY_UNAVAILABLE", "dataset", "/datasetDefinition", "available"));
			return false;
		}
		return true;
	}

	private ResolvedTarget resolveTarget(TargetRequest request, TrainingProjectVersion version,
			List<RunDefinitionFailure> failures) {
		if (request == null || request.targetClass() == null || !validGpuCount(request)) {
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
		List<EligibleTarget> compatible = matching.stream()
			.filter(candidate -> version.images().containsKey(candidate.acceleratorBackend()))
			.toList();
		if (compatible.isEmpty()) {
			failures.add(failure("PROJECT_CAPABILITIES_INCOMPATIBLE", "training-project-version", "/targetRequest",
					"acceleratorBackend"));
			return null;
		}
		List<String> models = compatible.stream().map(EligibleTarget::gpuModel).distinct().toList();
		String gpuModel = request.gpuModel() != null ? request.gpuModel()
				: models.size() == 1 ? models.getFirst() : null;
		return new ResolvedTarget(request, purchaseMode(request.targetClass()), gpuModel, compatible);
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

	private String resolveCurrency(List<RunDefinitionFailure> failures) {
		try {
			String currency = this.reportingCurrency.reportingCurrency();
			if (currency == null || !CURRENCY.matcher(currency).matches()
					|| !Currency.getAvailableCurrencies().contains(Currency.getInstance(currency))
					|| Currency.getInstance(currency).getDefaultFractionDigits() < 0) {
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

	private static void assessPrices(ResolvedTarget target, String reportingCurrency, Instant quoteTime,
			List<RunDefinitionFailure> failures) {
		if (target == null || reportingCurrency == null) {
			return;
		}
		for (EligibleTarget candidate : target.candidates()) {
			EligibleTargetPrice price = candidate.price();
			if (price == null) {
				failures.add(failure("PRICE_MISSING", "price-source", "/costQuote/candidates", "required"));
				continue;
			}
			if (price.nativeHourlyRate() == null || price.nativeHourlyRate().signum() < 0
					|| price.minimumQuantity() == null || price.minimumQuantity().signum() <= 0
					|| price.billingQuantum() == null || price.billingQuantum().signum() <= 0
					|| price.nativeCurrency() == null || !CURRENCY.matcher(price.nativeCurrency()).matches()) {
				failures.add(failure("PRICE_RATE_INVALID", "price-source", "/costQuote/candidates", "minimum"));
			}
			if (price.sourceId() == null || price.sourceRevision() < 1 || !validSourceKind(price.sourceKind())
					|| price.effectiveFrom() == null || price.observedFrom() == null || price.observedUntil() == null) {
				failures.add(failure("PRICE_PROVENANCE_INVALID", "price-source", "/costQuote/candidates/source",
						"required"));
			}
			if (price.effectiveFrom() != null && (price.effectiveFrom().isAfter(quoteTime)
					|| price.effectiveUntil() != null && !quoteTime.isBefore(price.effectiveUntil()))) {
				failures.add(failure("PRICE_NOT_EFFECTIVE", "price-source", "/costQuote/candidates/effectiveInterval",
						"contains"));
			}
			if (price.effectiveFrom() != null && price.effectiveUntil() != null
					&& !price.effectiveFrom().isBefore(price.effectiveUntil())) {
				failures.add(failure("PRICE_EFFECTIVE_INTERVAL_INVALID", "price-source",
						"/costQuote/candidates/effectiveInterval", "interval"));
			}
			if (price.conversionRate() == null || price.conversionRate().signum() <= 0
					|| price.conversionSourceId() == null || price.conversionSourceRevision() < 1
					|| !validSourceKind(price.conversionSourceKind()) || price.conversionObservedAt() == null) {
				failures.add(failure("CURRENCY_CONVERSION_MISSING", "price-source", "/costQuote/candidates/conversion",
						"required"));
			}
			if (price.maximumObservationAge() == null || price.maximumObservationAge().isNegative()
					|| price.maximumObservationAge().isZero() || price.observedUntil() == null
					|| price.observedUntil().plus(price.maximumObservationAge()).isBefore(quoteTime)
					|| price.conversionObservedAt() == null
					|| price.conversionObservedAt().plus(price.maximumObservationAge()).isBefore(quoteTime)) {
				failures.add(failure("PRICE_OBSERVATION_STALE", "price-source", "/costQuote/candidates",
						"maximumObservationAge"));
			}
		}
	}

	private static boolean validSourceKind(String value) {
		return value != null && Set.of("operator-schedule", "provider-api", "skypilot-catalog").contains(value);
	}

	private static void assessCheckpoint(RunSubmission submission, CheckpointSeedFacts seed,
			TrainingProjectVersion version, JsonNode configuration, boolean datasetReferenceValid,
			List<RunDefinitionFailure> failures) {
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
		if (datasetReferenceValid) {
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
			ResolvedTarget target, RunDefinitionStorageSelection storage, String currency, Instant quoteTime) {
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", 1);
		root.set("trainingProjectVersion", projectVersion(version));
		root.set("configuration", configuration.deepCopy());
		root.set("datasetDefinition", dataset(submission.datasetDefinition()));
		root.set("targetRequest", target(target));
		root.set("storage", storage(storage));
		root.set("costQuote", costQuote(target, currency, quoteTime));
		root.set("executionPolicy", executionPolicy(submission, currency));
		root.put("orderingReset", submission.orderingReset());
		return RunDefinition.from(root);
	}

	private static ObjectNode costQuote(ResolvedTarget target, String currency, Instant quoteTime) {
		ObjectNode result = JSON.createObjectNode();
		result.put("quoteTime", quoteTime.toString());
		result.putObject("reportingCurrency")
			.put("code", currency)
			.put("minorUnit", Currency.getInstance(currency).getDefaultFractionDigits());
		var candidates = result.putArray("candidates");
		List<BigDecimal> hourlyRates = new ArrayList<>();
		List<BigDecimal> dailyRates = new ArrayList<>();
		List<BigDecimal> weeklyRates = new ArrayList<>();
		for (EligibleTarget eligible : target.candidates()) {
			EligibleTargetPrice price = eligible.price();
			BigDecimal hourly = quotedCost(price, target.request().gpuCount(), 1);
			BigDecimal daily = quotedCost(price, target.request().gpuCount(), 24);
			BigDecimal weekly = quotedCost(price, target.request().gpuCount(), 168);
			hourlyRates.add(hourly);
			dailyRates.add(daily);
			weeklyRates.add(weekly);
			ObjectNode candidate = candidates.addObject()
				.put("target", eligible.target())
				.put("gpuModel", eligible.gpuModel())
				.put("gpuCount", target.request().gpuCount())
				.put("purchaseMode", target.purchaseMode())
				.put("reportingHourlyRate", hourly);
			candidate.putObject("nativeRate")
				.put("amount", price.nativeHourlyRate())
				.put("currency", price.nativeCurrency())
				.put("unit", "gpu-hour")
				.putObject("billingRules")
				.put("minimumQuantity", price.minimumQuantity())
				.put("billingQuantum", price.billingQuantum());
			provenance(candidate.putObject("source"), price.sourceId(), price.sourceRevision(), price.sourceKind());
			interval(candidate.putObject("effectiveInterval"), price.effectiveFrom(), price.effectiveUntil());
			interval(candidate.putObject("observationInterval"), price.observedFrom(), price.observedUntil());
			ObjectNode conversion = candidate.putObject("conversion")
				.put("rate", price.conversionRate())
				.put("observedAt", price.conversionObservedAt().toString())
				.put("maximumObservationAge", price.maximumObservationAge().toString());
			provenance(conversion.putObject("source"), price.conversionSourceId(), price.conversionSourceRevision(),
					price.conversionSourceKind());
		}
		range(result.putObject("hourly"), minimum(hourlyRates), maximum(hourlyRates));
		range(result.putObject("daily"), minimum(dailyRates), maximum(dailyRates));
		range(result.putObject("weekly"), minimum(weeklyRates), maximum(weeklyRates));
		return result;
	}

	private static BigDecimal quotedCost(EligibleTargetPrice price, int gpuCount, int hours) {
		BigDecimal requestedQuantity = BigDecimal.valueOf(gpuCount).multiply(BigDecimal.valueOf(hours));
		BigDecimal minimumQuantity = requestedQuantity.max(price.minimumQuantity());
		BigDecimal billableQuantity = minimumQuantity.divide(price.billingQuantum(), 0, RoundingMode.CEILING)
			.multiply(price.billingQuantum());
		return price.nativeHourlyRate().multiply(billableQuantity).multiply(price.conversionRate());
	}

	private static BigDecimal minimum(List<BigDecimal> values) {
		return values.stream().min(BigDecimal::compareTo).orElseThrow();
	}

	private static BigDecimal maximum(List<BigDecimal> values) {
		return values.stream().max(BigDecimal::compareTo).orElseThrow();
	}

	private static void provenance(ObjectNode result, java.util.UUID id, long revision, String kind) {
		result.put("sourceId", id.toString()).put("revision", revision).put("kind", kind);
	}

	private static void interval(ObjectNode result, Instant from, Instant until) {
		result.put("from", from.toString());
		if (until == null) {
			result.putNull("until");
		}
		else {
			result.put("until", until.toString());
		}
	}

	private static void range(ObjectNode result, BigDecimal minimum, BigDecimal maximum) {
		result.put("minimum", minimum).put("maximum", maximum);
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
		ObjectNode profiles = result.putObject("environmentProfiles");
		new TreeMap<>(version.environmentProfiles()).forEach(profiles::put);
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
			.put("gpuCount", request.gpuCount());
		if (request.minimumGpuMemoryBytes() != null) {
			result.put("minimumGpuMemoryBytes", request.minimumGpuMemoryBytes());
		}
		if (request.target() != null) {
			result.put("target", request.target());
		}
		if (resolved.gpuModel() != null) {
			result.put("gpuModel", resolved.gpuModel());
		}
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

	private static boolean validGpuCount(TargetRequest request) {
		return request.gpuCount() > 0
				&& (request.targetClass() != TargetClass.LOCAL_SINGLE_GPU || request.gpuCount() == 1)
				&& (request.targetClass() != TargetClass.LOCAL_MULTI_GPU || request.gpuCount() >= 2);
	}

	private record ResolvedTarget(TargetRequest request, String purchaseMode, String gpuModel,
			List<EligibleTarget> candidates) {
	}

}
