package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingCatalogue;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PriceSourceRegistry {

	private final EntityManager entities;

	private final Clock clock;

	private final SkyPilotCatalogue skyPilotCatalogue;

	private final EligibleGpuOfferingCatalogue gpuOfferings;

	@Autowired
	PriceSourceRegistry(EntityManager entities, SkyPilotCatalogue skyPilotCatalogue,
			EligibleGpuOfferingCatalogue gpuOfferings) {
		this(entities, Clock.systemUTC(), skyPilotCatalogue, gpuOfferings);
	}

	PriceSourceRegistry(EntityManager entities, Clock clock, SkyPilotCatalogue skyPilotCatalogue,
			EligibleGpuOfferingCatalogue gpuOfferings) {
		this.entities = entities;
		this.clock = clock;
		this.skyPilotCatalogue = skyPilotCatalogue;
		this.gpuOfferings = gpuOfferings;
	}

	public UUID register(String name, String kind, UUID credentialBindingId, Map<String, Object> configuration) {
		requireName(name);
		PriceSourceKind parsedKind = requireKind(kind);
		if (!this.entities
			.createQuery("select source.id from PriceSourceEntity source where source.name = :name", UUID.class)
			.setParameter("name", name)
			.getResultList()
			.isEmpty()) {
			throw new PriceSourceConflictException("PRICE_SOURCE_NAME_CONFLICT",
					"A Price Source with this name already exists");
		}
		Map<String, Object> validated = PriceSourceConfiguration.validate(parsedKind, configuration);
		UUID id = UUID.randomUUID();
		this.entities.persist(PriceSourceEntity.create(id, name, parsedKind, credentialBindingId, validated));
		return id;
	}

	public void stage(UUID id, long expectedRegistrationRevision, Map<String, Object> configuration) {
		PriceSourceEntity source = source(id);
		requireRevision(source, expectedRegistrationRevision);
		long revision = source.revisions.getLast().revision + 1;
		source.revisions
			.add(new PriceSourceRevisionValue(revision, PriceSourceConfiguration.validate(source.kind, configuration)));
		source.candidateRevision = revision;
		source.registrationRevision++;
	}

	public void assess(UUID id) {
		PriceSourceEntity source = source(id);
		if (source.candidateRevision == null) {
			throw new PriceSourceValidationException("PRICE_SOURCE_CANDIDATE_MISSING",
					"There is no candidate revision to assess");
		}
		Instant started = this.clock.instant();
		Map<String, Object> configuration = source.revisions.stream()
			.filter(revision -> revision.revision == source.candidateRevision.longValue())
			.findFirst()
			.orElseThrow().configuration;
		List<String> capabilities = assess(source, configuration, started);
		boolean successful = capabilities.stream().noneMatch(value -> value.startsWith("failed:"));
		source.assessments.add(new PriceSourceAssessmentValue(UUID.randomUUID(), source.candidateRevision, successful,
				capabilities, started, this.clock.instant()));
		source.assessedScheduleRevision = successful ? source.scheduleRevision : null;
		source.registrationRevision++;
	}

	public void promote(UUID id, long expectedRegistrationRevision, long revision) {
		PriceSourceEntity source = source(id);
		requireRevision(source, expectedRegistrationRevision);
		if (source.candidateRevision == null || source.candidateRevision != revision) {
			throw new PriceSourceConflictException("PRICE_SOURCE_CANDIDATE_CHANGED",
					"The requested revision is not the current candidate");
		}
		boolean successful = source.assessedScheduleRevision != null
				&& source.assessedScheduleRevision == source.scheduleRevision && source.assessments.stream()
					.anyMatch(assessment -> assessment.revision == revision && assessment.successful);
		if (!successful) {
			throw new PriceSourceValidationException("PRICE_SOURCE_ASSESSMENT_REQUIRED",
					"Promotion requires a successful assessment of the candidate revision");
		}
		Long previousActiveRevision = source.activeRevision;
		source.activeRevision = revision;
		source.candidateRevision = null;
		source.registrationRevision++;
		if (previousActiveRevision != null) {
			this.entities
				.createQuery("select binding from PriceSourceBindingEntity binding where binding.sourceId = :sourceId "
						+ "and binding.sourceRevision = :sourceRevision", PriceSourceBindingEntity.class)
				.setParameter("sourceId", source.id)
				.setParameter("sourceRevision", previousActiveRevision)
				.getResultList()
				.forEach(binding -> {
					binding.sourceRevision = revision;
					binding.bindingRevision++;
				});
		}
	}

	public PriceSourceBindingView bind(String bindingKey, UUID sourceId, long sourceRevision,
			Duration maximumObservationAge, Long expectedBindingRevision) {
		PriceSourceBindingKey parsedKey;
		try {
			parsedKey = PriceSourceBindingKey.parse(bindingKey);
		}
		catch (IllegalArgumentException failure) {
			throw new PriceSourceValidationException("PRICE_SOURCE_BINDING_KEY_INVALID",
					"Binding keys identify one target resource family or one currency pair");
		}
		if (maximumObservationAge == null || maximumObservationAge.isZero() || maximumObservationAge.isNegative()) {
			throw new PriceSourceValidationException("PRICE_SOURCE_FRESHNESS_INVALID",
					"Maximum observation age must be positive");
		}
		PriceSourceEntity source = source(sourceId);
		if (source.activeRevision == null || source.activeRevision != sourceRevision) {
			throw new PriceSourceValidationException("PRICE_SOURCE_REVISION_NOT_ACTIVE",
					"A binding must select the source's active revision");
		}
		if (parsedKey instanceof PriceSourceBindingKey.CurrencyPair
				&& !assessedForCapability(source, sourceRevision, parsedKey.value())) {
			throw new PriceSourceValidationException("PRICE_SOURCE_CAPABILITY_UNAVAILABLE",
					"The active Price Source revision cannot provide the bound currency pair");
		}
		if (source.kind == PriceSourceKind.SKYPILOT_CATALOG && parsedKey instanceof PriceSourceBindingKey.TargetResource
				&& !assessedForCapability(source, sourceRevision, parsedKey.value())) {
			throw new PriceSourceValidationException("PRICE_SOURCE_CAPABILITY_UNAVAILABLE",
					"The active SkyPilot catalogue revision cannot price the bound target");
		}
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class, parsedKey.value());
		if (binding == null) {
			if (expectedBindingRevision != null) {
				throw new PriceSourceConflictException("PRICE_SOURCE_BINDING_REVISION_CONFLICT",
						"The Price Source binding changed; reload it and retry");
			}
			binding = new PriceSourceBindingEntity(parsedKey.value(), 1, sourceId, sourceRevision,
					maximumObservationAge);
			this.entities.persist(binding);
		}
		else {
			if (expectedBindingRevision == null || binding.bindingRevision != expectedBindingRevision) {
				throw new PriceSourceConflictException("PRICE_SOURCE_BINDING_REVISION_CONFLICT",
						"The Price Source binding changed; reload it and retry");
			}
			binding.bindingRevision++;
			binding.sourceId = sourceId;
			binding.sourceRevision = sourceRevision;
			binding.maximumObservationAge = maximumObservationAge.toString();
		}
		return binding.view();
	}

	@Transactional(readOnly = true)
	public PriceSourceView get(UUID id) {
		return view(source(id));
	}

	@Transactional(readOnly = true)
	public List<PriceSourceView> list() {
		return this.entities
			.createQuery("select source from PriceSourceEntity source order by source.name", PriceSourceEntity.class)
			.getResultStream()
			.map(PriceSourceRegistry::view)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<PriceSourceBindingView> bindings() {
		return this.entities
			.createQuery("select binding from PriceSourceBindingEntity binding order by binding.bindingKey",
					PriceSourceBindingEntity.class)
			.getResultStream()
			.map(PriceSourceBindingEntity::view)
			.toList();
	}

	private PriceSourceEntity source(UUID id) {
		PriceSourceEntity result = this.entities.find(PriceSourceEntity.class, id);
		if (result == null) {
			throw new PriceSourceNotFoundException();
		}
		return result;
	}

	private static PriceSourceView view(PriceSourceEntity source) {
		return new PriceSourceView(source.id, source.name, source.kind, source.registrationRevision,
				source.activeRevision, source.candidateRevision, source.credentialBindingId,
				source.revisions.stream()
					.map(value -> new PriceSourceRevisionView(value.revision, Map.copyOf(value.configuration)))
					.toList(),
				source.assessments.stream()
					.map(value -> new PriceSourceAssessmentView(value.id, value.revision, value.successful,
							List.copyOf(value.capabilityResults), value.observedFrom, value.observedUntil))
					.toList());
	}

	private List<String> assess(PriceSourceEntity source, Map<String, Object> configuration, Instant observedAt) {
		List<String> results = new ArrayList<>();
		switch (source.kind) {
			case OPERATOR_SCHEDULE -> assessOperatorSchedule(source, configuration, results);
			case SKYPILOT_CATALOG -> assessSkyPilot(configuration, observedAt, results);
			case PROVIDER_API -> assessProvider(configuration, results);
		}
		Object capabilities = configuration.get("capabilities");
		results.add(capabilities instanceof List<?> values && !values.isEmpty() ? "passed:capabilities"
				: "failed:capabilities-required");
		java.util.stream.Stream<String> declaredPairs = capabilities instanceof List<?> values ? values.stream()
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(value -> value.startsWith("currency:")) : java.util.stream.Stream.empty();
		java.util.stream.Stream<String> boundPairs = this.entities
			.createQuery(
					"select binding.bindingKey from PriceSourceBindingEntity binding "
							+ "where binding.sourceId = :sourceId and binding.bindingKey like 'currency:%'",
					String.class)
			.setParameter("sourceId", source.id)
			.getResultStream();
		java.util.stream.Stream.concat(declaredPairs, boundPairs)
			.distinct()
			.forEach(
					pair -> results.add(canProvide(source, configuration, pair) ? "passed:" + pair : "failed:" + pair));
		return results;
	}

	private void assessOperatorSchedule(PriceSourceEntity source, Map<String, Object> configuration,
			List<String> results) {
		Object rates = configuration.get("rates");
		List<GpuPriceScheduleEntryEntity> schedule = this.entities
			.createQuery(
					"select entry from GpuPriceScheduleEntryEntity entry "
							+ "where entry.sourceId = :sourceId and entry.sourceRevision = :sourceRevision",
					GpuPriceScheduleEntryEntity.class)
			.setParameter("sourceId", source.id)
			.setParameter("sourceRevision", source.candidateRevision)
			.getResultList();
		boolean legacyRatesValid = rates instanceof List<?> values && !values.isEmpty()
				&& values.stream().allMatch(PriceSourceRegistry::validRate);
		if (rates != null) {
			results.add(legacyRatesValid ? "passed:rates" : "failed:rates-required");
		}
		else {
			results.add(validSchedule(schedule, configuration) ? "passed:gpu-compute-rates"
					: "failed:gpu-compute-rates-required");
		}
	}

	private static void assessProvider(Map<String, Object> configuration, List<String> results) {
		Object endpoint = configuration.get("endpoint");
		results.add(endpoint instanceof String value && (value.startsWith("https://") || value.startsWith("http://"))
				? "passed:endpoint" : "failed:endpoint-required");
	}

	private void assessSkyPilot(Map<String, Object> configuration, Instant observedAt, List<String> results) {
		Set<String> capabilities = declarations(configuration, "capabilities", null);
		Set<String> targets = declarations(configuration, "targets", null);
		Set<String> currencies = declarations(configuration, "nativeCurrencies", "currencies");
		Set<String> units = declarations(configuration, "nativeUnits", "units");
		results.add(capabilities.contains("gpu-compute") ? "passed:gpu-compute" : "failed:gpu-compute-required");
		results.add(Set.of("USD").equals(currencies) ? "passed:native-currency:USD"
				: "failed:native-currency-USD-required");
		results.add(Set.of("instance-hour").equals(units) ? "passed:native-unit:instance-hour"
				: "failed:native-unit-instance-hour-required");
		if (targets.isEmpty()) {
			results.add("failed:targets-required");
			return;
		}
		for (String target : targets.stream().sorted().toList()) {
			assessSkyPilotTarget(target, observedAt, results);
		}
	}

	private void assessSkyPilotTarget(String target, Instant observedAt, List<String> results) {
		String bindingKey;
		try {
			bindingKey = PriceSourceBindingKey
				.gpuCompute(new de.zorro909.skywright.backend.target.TargetIdentity(target))
				.value();
		}
		catch (IllegalArgumentException failure) {
			results.add("failed:target:" + target + ":resource:gpu-compute");
			return;
		}
		List<de.zorro909.skywright.backend.gpuoffering.EligibleGpuOfferingView> offerings = this.gpuOfferings
			.admissibleForTarget(target)
			.stream()
			.sorted(java.util.Comparator.comparing(offering -> offering.id().toString()))
			.toList();
		if (offerings.isEmpty()) {
			results.add("failed:" + bindingKey);
			return;
		}
		boolean targetPassed = true;
		for (var offering : offerings) {
			boolean offeringPassed;
			try {
				offeringPassed = this.skyPilotCatalogue
					.price(new SkyPilotCatalogueQuery(offering.target(), offering.region(), offering.instanceType(),
							offering.gpuModel(), offering.gpuCount(),
							"spot".equals(offering.purchaseMode().wireValue()), observedAt))
					.isPresent();
			}
			catch (Exception failure) {
				offeringPassed = false;
			}
			results.add((offeringPassed ? "passed:" : "failed:") + "gpu-offering:" + offering.id() + ":revision:"
					+ offering.revision());
			targetPassed &= offeringPassed;
		}
		results.add((targetPassed ? "passed:" : "failed:") + bindingKey);
	}

	private boolean canProvide(PriceSourceEntity source, Map<String, Object> configuration, String bindingKey) {
		Object capabilities = configuration.get("capabilities");
		boolean declared = capabilities instanceof List<?> values && values.contains(bindingKey);
		if (!declared || source.kind != PriceSourceKind.OPERATOR_SCHEDULE) {
			return declared;
		}
		PriceSourceBindingKey parsed;
		try {
			parsed = PriceSourceBindingKey.parse(bindingKey);
		}
		catch (IllegalArgumentException failure) {
			return false;
		}
		if (!(parsed instanceof PriceSourceBindingKey.CurrencyPair pair)
				|| pair.nativeCurrency().equals(pair.reportingCurrency())) {
			return false;
		}
		return this.entities.createQuery("""
				select count(conversion) from CurrencyConversionEntity conversion
				where conversion.sourceId = :sourceId
				  and conversion.nativeCurrency = :nativeCurrency
				  and conversion.reportingCurrency = :reportingCurrency
				""", Long.class)
			.setParameter("sourceId", source.id)
			.setParameter("nativeCurrency", pair.nativeCurrency())
			.setParameter("reportingCurrency", pair.reportingCurrency())
			.getSingleResult() > 0;
	}

	private static boolean validCurrency(String value) {
		try {
			return value.matches("[A-Z]{3}") && Currency.getInstance(value).getCurrencyCode().equals(value);
		}
		catch (IllegalArgumentException failure) {
			return false;
		}
	}

	private static boolean assessedForCapability(PriceSourceEntity source, long revision, String bindingKey) {
		return source.assessments.stream()
			.anyMatch(assessment -> assessment.revision == revision && assessment.successful
					&& assessment.capabilityResults.contains("passed:" + bindingKey));
	}

	private static boolean validSchedule(List<GpuPriceScheduleEntryEntity> entries, Map<String, Object> configuration) {
		if (entries.isEmpty()) {
			return false;
		}
		Set<String> currencies = declarations(configuration, "nativeCurrencies", "currencies");
		Set<String> units = declarations(configuration, "nativeUnits", "units");
		boolean entriesMatch = entries.stream()
			.allMatch(entry -> (currencies.isEmpty() || currencies.contains(entry.nativeCurrency))
					&& (units.isEmpty() || units.contains(entry.nativeUnit)));
		boolean declarationsCovered = (currencies.isEmpty() || currencies.stream()
			.allMatch(currency -> entries.stream().anyMatch(entry -> entry.nativeCurrency.equals(currency))))
				&& (units.isEmpty() || units.stream()
					.allMatch(unit -> entries.stream().anyMatch(entry -> entry.nativeUnit.equals(unit))));
		return entriesMatch && declarationsCovered;
	}

	private static Set<String> declarations(Map<String, Object> configuration, String primaryKey, String legacyKey) {
		Object value = configuration.containsKey(primaryKey) || legacyKey == null ? configuration.get(primaryKey)
				: configuration.get(legacyKey);
		if (!(value instanceof List<?> values)) {
			return Set.of();
		}
		return values.stream()
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static boolean validRate(Object value) {
		if (!(value instanceof Map<?, ?> rate)) {
			return false;
		}
		try {
			Object amount = rate.get("amount");
			BigDecimal decimal = amount instanceof Number || amount instanceof String
					? new BigDecimal(amount.toString()) : null;
			return decimal != null && decimal.signum() >= 0 && rate.get("currency") instanceof String currency
					&& Currency.getInstance(currency).getCurrencyCode().equals(currency);
		}
		catch (IllegalArgumentException error) {
			return false;
		}
	}

	private static void requireRevision(PriceSourceEntity source, long expected) {
		if (source.registrationRevision != expected) {
			throw new PriceSourceConflictException("PRICE_SOURCE_REVISION_CONFLICT",
					"The Price Source changed; reload it and retry");
		}
	}

	private static void requireName(String name) {
		if (name == null || name.isBlank() || name.length() > 255) {
			throw new PriceSourceValidationException("PRICE_SOURCE_NAME_INVALID",
					"Name must contain between 1 and 255 characters");
		}
	}

	private static PriceSourceKind requireKind(String kind) {
		try {
			return PriceSourceKind.parse(kind);
		}
		catch (IllegalArgumentException failure) {
			throw new PriceSourceValidationException("PRICE_SOURCE_KIND_INVALID", "Price Source kind is not supported");
		}
	}

}
