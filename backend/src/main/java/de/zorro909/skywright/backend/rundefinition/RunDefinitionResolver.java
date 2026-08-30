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
import de.zorro909.skywright.backend.target.TargetIdentity;
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

	private final CostQuoteReader costQuotes;

	private final Clock clock;

	public RunDefinitionResolver(TrainingProjectVersions projectVersions, DatasetDefinitionReader datasets,
			TargetEligibilityReader targetEligibility, RunDefinitionStorageReader targetStorages,
			ReportingCurrencyReader reportingCurrency, CostQuoteReader costQuotes) {
		this.projectVersions = projectVersions;
		this.datasets = datasets;
		this.targetEligibility = targetEligibility;
		this.targetStorages = targetStorages;
		this.reportingCurrency = reportingCurrency;
		this.costQuotes = costQuotes;
		this.clock = Clock.systemUTC();
	}

	RunDefinitionResolver(TrainingProjectVersions projectVersions, DatasetDefinitionReader datasets,
			TargetEligibilityReader targetEligibility, RunDefinitionStorageReader targetStorages,
			ReportingCurrencyReader reportingCurrency, CostQuoteReader costQuotes, Clock clock) {
		this.projectVersions = projectVersions;
		this.datasets = datasets;
		this.targetEligibility = targetEligibility;
		this.targetStorages = targetStorages;
		this.reportingCurrency = reportingCurrency;
		this.costQuotes = costQuotes;
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
		CostQuoteAssessment quote = resolveQuote(submission.targetRequest(), target, currency, quoteTime, failures);
		assessCheckpoint(submission, checkpointSeed, version, configuration, datasetReferenceValid, failures);

		List<RunDefinitionFailure> ordered = failures.stream().distinct().sorted().toList();
		if (!ordered.isEmpty()) {
			return new RunDefinitionResolution(null, ordered);
		}
		try {
			return new RunDefinitionResolution(
					build(submission, version, configuration, target, storage, currency, quoteTime, quote), List.of());
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
		if (target != null && target.target() != null && !TargetIdentity.valid(target.target())) {
			failures.add(failure("TARGET_IDENTITY_INVALID", "submission", "/targetRequest/target", "pattern"));
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

	private CostQuoteAssessment resolveQuote(TargetRequest request, ResolvedTarget target, String reportingCurrency,
			Instant quoteTime, List<RunDefinitionFailure> failures) {
		if (request == null || request.targetClass() == null || !cloud(request.targetClass())) {
			return null;
		}
		if (target == null || reportingCurrency == null) {
			return null;
		}
		try {
			CostQuoteAssessment quote = this.costQuotes.resolve(request, reportingCurrency, quoteTime);
			failures.addAll(quote.failures());
			if (quote.failures().isEmpty() && quote.candidates().isEmpty()) {
				failures
					.add(failure("GPU_OFFERING_NONE_ELIGIBLE", "gpu-offering-catalogue", "/targetRequest", "eligible"));
			}
			for (int index = 0; index < quote.candidates().size(); index++) {
				assessCandidate(quote.candidates().get(index), request, reportingCurrency, quoteTime, index, failures);
			}
			return failures.isEmpty() ? quote : null;
		}
		catch (RuntimeException error) {
			failures.add(failure("COST_QUOTE_UNAVAILABLE", "cost-quote", "/costQuote", "available"));
			return null;
		}
	}

	private static void assessCandidate(CostQuoteCandidate candidate, TargetRequest request, String reportingCurrency,
			Instant quoteTime, int index, List<RunDefinitionFailure> failures) {
		String pointer = "/costQuote/candidates/" + index;
		if (candidate == null || candidate.offeringId() == null || candidate.offeringRevision() < 1
				|| candidate.targetClass() == null || !TargetIdentity.valid(candidate.target())
				|| blank(candidate.providerOfferingId()) || blank(candidate.region()) || blank(candidate.instanceType())
				|| blank(candidate.gpuModel()) || candidate.gpuCount() <= 0 || candidate.gpuMemoryBytes() <= 0
				|| blank(candidate.purchaseMode()) || blank(candidate.supportTier())) {
			failures.add(failure("GPU_OFFERING_SNAPSHOT_INVALID", "gpu-offering-catalogue", pointer + "/offering",
					"required"));
			return;
		}
		if (candidate.targetClass() != request.targetClass() || candidate.gpuCount() < request.gpuCount()
				|| !candidate.purchaseMode().equals(purchaseMode(request.targetClass()))
				|| request.minimumGpuMemoryBytes() != null
						&& candidate.gpuMemoryBytes() < request.minimumGpuMemoryBytes()
				|| request.target() != null && !request.target().equals(candidate.target())
				|| request.gpuModel() != null && !request.gpuModel().equals(candidate.gpuModel())) {
			failures.add(failure("GPU_OFFERING_SNAPSHOT_INELIGIBLE", "gpu-offering-catalogue", pointer + "/offering",
					"eligible"));
		}
		if (candidate.nativeRate() == null || candidate.nativeRate().signum() < 0 || candidate.minimumQuantity() == null
				|| candidate.minimumQuantity().signum() <= 0 || candidate.billingQuantum() == null
				|| candidate.billingQuantum().signum() <= 0 || !validCurrency(candidate.nativeCurrency())
				|| !"instance-hour".equals(candidate.nativeUnit()) || candidate.provenance() == null) {
			failures.add(failure("PRICE_RATE_INVALID", "price-source", pointer + "/nativeRate", "required"));
		}
		if (candidate.sourceId() == null || candidate.sourceRevision() < 1 || !validSourceKind(candidate.sourceKind())
				|| candidate.sourceObservedFrom() == null || candidate.sourceObservedUntil() == null
				|| candidate.rateObservedAt() == null || candidate.maximumObservationAge() == null
				|| candidate.maximumObservationAge().isNegative() || candidate.maximumObservationAge().isZero()) {
			failures.add(failure("PRICE_PROVENANCE_INVALID", "price-source", pointer + "/source", "required"));
		}
		if (candidate.effectiveFrom() == null || candidate.effectiveFrom().isAfter(quoteTime)
				|| candidate.effectiveUntil() != null && !quoteTime.isBefore(candidate.effectiveUntil())) {
			failures.add(failure("PRICE_NOT_EFFECTIVE", "price-source", pointer + "/effectiveInterval", "contains"));
		}
		if (candidate.effectiveFrom() != null && candidate.effectiveUntil() != null
				&& !candidate.effectiveFrom().isBefore(candidate.effectiveUntil())) {
			failures.add(failure("PRICE_EFFECTIVE_INTERVAL_INVALID", "price-source", pointer + "/effectiveInterval",
					"interval"));
		}
		if (candidate.rateObservedAt() != null && candidate.maximumObservationAge() != null
				&& candidate.rateObservedAt().plus(candidate.maximumObservationAge()).isBefore(quoteTime)) {
			failures.add(failure("GPU_COMPUTE_PRICE_STALE", "price-source", pointer, "maximumObservationAge"));
		}
		if (reportingCurrency.equals(candidate.nativeCurrency())) {
			if (candidate.conversion() != null) {
				failures
					.add(failure("CURRENCY_CONVERSION_UNEXPECTED", "price-source", pointer + "/conversion", "absent"));
			}
		}
		else {
			assessConversion(candidate.conversion(), candidate.nativeCurrency(), reportingCurrency, quoteTime, pointer,
					failures);
		}
	}

	private static void assessConversion(CostQuoteConversion conversion, String nativeCurrency,
			String reportingCurrency, Instant quoteTime, String pointer, List<RunDefinitionFailure> failures) {
		String conversionPointer = pointer + "/conversion";
		if (conversion == null) {
			failures.add(failure("CURRENCY_CONVERSION_MISSING", "price-source", conversionPointer, "required"));
			return;
		}
		if (!nativeCurrency.equals(conversion.nativeCurrency())
				|| !reportingCurrency.equals(conversion.reportingCurrency()) || conversion.rate() == null
				|| conversion.rate().signum() <= 0 || conversion.provenance() == null) {
			failures.add(failure("CURRENCY_CONVERSION_INVALID", "price-source", conversionPointer, "required"));
		}
		if (conversion.sourceId() == null || conversion.sourceRevision() < 1 || conversion.scheduleRevision() < 1
				|| !validSourceKind(conversion.sourceKind()) || conversion.sourceObservedFrom() == null
				|| conversion.sourceObservedUntil() == null || conversion.observedAt() == null
				|| conversion.maximumObservationAge() == null || conversion.maximumObservationAge().isNegative()
				|| conversion.maximumObservationAge().isZero()) {
			failures.add(failure("CURRENCY_CONVERSION_PROVENANCE_INVALID", "price-source",
					conversionPointer + "/source", "required"));
		}
		if (conversion.effectiveFrom() == null || conversion.effectiveFrom().isAfter(quoteTime)
				|| conversion.effectiveUntil() != null && quoteTime.isAfter(conversion.effectiveUntil())) {
			failures.add(failure("CURRENCY_CONVERSION_NOT_EFFECTIVE", "price-source",
					conversionPointer + "/effectiveInterval", "contains"));
		}
		if (conversion.effectiveFrom() != null && conversion.effectiveUntil() != null
				&& !conversion.effectiveFrom().isBefore(conversion.effectiveUntil())) {
			failures.add(failure("CURRENCY_CONVERSION_EFFECTIVE_INTERVAL_INVALID", "price-source",
					conversionPointer + "/effectiveInterval", "interval"));
		}
		if (conversion.observedAt() != null && conversion.maximumObservationAge() != null
				&& conversion.observedAt().plus(conversion.maximumObservationAge()).isBefore(quoteTime)) {
			failures
				.add(failure("CURRENCY_CONVERSION_STALE", "price-source", conversionPointer, "maximumObservationAge"));
		}
	}

	private static boolean validCurrency(String value) {
		try {
			return value != null && CURRENCY.matcher(value).matches()
					&& Currency.getInstance(value).getCurrencyCode().equals(value);
		}
		catch (IllegalArgumentException failure) {
			return false;
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
			ResolvedTarget target, RunDefinitionStorageSelection storage, String currency, Instant quoteTime,
			CostQuoteAssessment quote) {
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", 2);
		root.set("trainingProjectVersion", projectVersion(version));
		root.set("configuration", configuration.deepCopy());
		root.set("datasetDefinition", dataset(submission.datasetDefinition()));
		root.set("targetRequest", target(target));
		root.set("storage", storage(storage));
		if (quote != null) {
			root.set("costQuote", costQuote(quote, currency, quoteTime));
		}
		root.set("executionPolicy", executionPolicy(submission, currency));
		root.put("orderingReset", submission.orderingReset());
		return RunDefinition.from(root);
	}

	private static ObjectNode costQuote(CostQuoteAssessment quote, String currency, Instant quoteTime) {
		ObjectNode result = JSON.createObjectNode();
		result.put("quoteTime", quoteTime.toString());
		int minorUnit = Currency.getInstance(currency).getDefaultFractionDigits();
		result.putObject("reportingCurrency").put("code", currency).put("minorUnit", minorUnit);
		var candidates = result.putArray("candidates");
		List<BigDecimal> hourlyRates = new ArrayList<>();
		List<BigDecimal> dailyRates = new ArrayList<>();
		List<BigDecimal> weeklyRates = new ArrayList<>();
		for (CostQuoteCandidate price : quote.candidates()) {
			BigDecimal hourly = quotedCost(price, 1);
			BigDecimal daily = quotedCost(price, 24);
			BigDecimal weekly = quotedCost(price, 168);
			hourlyRates.add(hourly);
			dailyRates.add(daily);
			weeklyRates.add(weekly);
			ObjectNode candidate = candidates.addObject()
				.put("rateObservedAt", price.rateObservedAt().toString())
				.put("maximumObservationAge", price.maximumObservationAge().toString())
				.put("reportingHourlyRate", present(hourly, minorUnit));
			candidate.putObject("offering")
				.put("id", price.offeringId().toString())
				.put("revision", price.offeringRevision())
				.put("targetClass", price.targetClass().wireValue())
				.put("target", price.target())
				.put("providerOfferingId", price.providerOfferingId())
				.put("region", price.region())
				.put("instanceType", price.instanceType())
				.put("gpuModel", price.gpuModel())
				.put("gpuCount", price.gpuCount())
				.put("gpuMemoryBytes", price.gpuMemoryBytes())
				.put("purchaseMode", price.purchaseMode())
				.put("supportTier", price.supportTier());
			candidate.putObject("nativeRate")
				.put("amount", price.nativeRate())
				.put("currency", price.nativeCurrency())
				.put("unit", price.nativeUnit())
				.set("provenance", JSON.valueToTree(price.provenance()));
			candidate.withObject("/nativeRate")
				.putObject("billingRules")
				.put("minimumQuantity", price.minimumQuantity())
				.put("billingQuantum", price.billingQuantum());
			provenance(candidate.putObject("source"), price.sourceId(), price.sourceRevision(), price.sourceKind());
			interval(candidate.putObject("effectiveInterval"), price.effectiveFrom(), price.effectiveUntil());
			interval(candidate.putObject("observationInterval"), price.sourceObservedFrom(),
					price.sourceObservedUntil());
			if (price.conversion() != null) {
				conversion(candidate.putObject("conversion"), price.conversion());
			}
		}
		range(result.putObject("hourly"), minimum(hourlyRates), maximum(hourlyRates), minorUnit);
		range(result.putObject("daily"), minimum(dailyRates), maximum(dailyRates), minorUnit);
		range(result.putObject("weekly"), minimum(weeklyRates), maximum(weeklyRates), minorUnit);
		return result;
	}

	private static BigDecimal quotedCost(CostQuoteCandidate price, int hours) {
		BigDecimal requestedQuantity = BigDecimal.valueOf(hours);
		BigDecimal minimumQuantity = requestedQuantity.max(price.minimumQuantity());
		BigDecimal billableQuantity = minimumQuantity.divide(price.billingQuantum(), 0, RoundingMode.CEILING)
			.multiply(price.billingQuantum());
		BigDecimal nativeAmount = price.nativeRate().multiply(billableQuantity);
		return price.conversion() == null ? nativeAmount : nativeAmount.multiply(price.conversion().rate());
	}

	private static void conversion(ObjectNode result, CostQuoteConversion conversion) {
		result.put("nativeCurrency", conversion.nativeCurrency())
			.put("reportingCurrency", conversion.reportingCurrency())
			.put("rate", conversion.rate())
			.put("scheduleRevision", conversion.scheduleRevision())
			.put("observedAt", conversion.observedAt().toString())
			.put("maximumObservationAge", conversion.maximumObservationAge().toString());
		result.set("provenance", JSON.valueToTree(conversion.provenance()));
		provenance(result.putObject("source"), conversion.sourceId(), conversion.sourceRevision(),
				conversion.sourceKind());
		interval(result.putObject("effectiveInterval"), conversion.effectiveFrom(), conversion.effectiveUntil());
		interval(result.putObject("observationInterval"), conversion.sourceObservedFrom(),
				conversion.sourceObservedUntil());
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

	private static void range(ObjectNode result, BigDecimal minimum, BigDecimal maximum, int minorUnit) {
		result.put("minimum", present(minimum, minorUnit)).put("maximum", present(maximum, minorUnit));
	}

	private static BigDecimal present(BigDecimal amount, int minorUnit) {
		return amount.setScale(minorUnit, RoundingMode.HALF_EVEN);
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

	private static boolean cloud(TargetClass targetClass) {
		return targetClass == TargetClass.CLOUD_ON_DEMAND || targetClass == TargetClass.CLOUD_SPOT;
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
