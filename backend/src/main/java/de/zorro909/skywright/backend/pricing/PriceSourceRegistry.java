package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class PriceSourceRegistry {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern BINDING_KEY = Pattern
		.compile("(?:target:[A-Za-z0-9._-]+:resource:[a-z][a-z0-9-]*|currency:[A-Z]{3}:[A-Z]{3})");

	private static final Set<String> SECRET_KEYS = Set.of("secret", "password", "token", "apikey", "api-key",
			"privatekey", "private-key", "credential", "credentials");

	private final EntityManager entities;

	private final Clock clock;

	@Autowired
	PriceSourceRegistry(EntityManager entities) {
		this(entities, Clock.systemUTC());
	}

	PriceSourceRegistry(EntityManager entities, Clock clock) {
		this.entities = entities;
		this.clock = clock;
	}

	public UUID register(String name, String kind, UUID credentialBindingId, Map<String, Object> configuration) {
		requireName(name);
		requireKind(kind);
		if (!this.entities
			.createQuery("select source.id from PriceSourceEntity source where source.name = :name", UUID.class)
			.setParameter("name", name)
			.getResultList()
			.isEmpty()) {
			throw new PriceSourceConflictException("PRICE_SOURCE_NAME_CONFLICT",
					"A Price Source with this name already exists");
		}
		String encoded = encodeSafe(configuration);
		UUID id = UUID.randomUUID();
		this.entities.persist(PriceSourceEntity.create(id, name, kind, credentialBindingId, encoded));
		return id;
	}

	public void stage(UUID id, long expectedRegistrationRevision, Map<String, Object> configuration) {
		PriceSourceEntity source = source(id);
		requireRevision(source, expectedRegistrationRevision);
		long revision = source.revisions.getLast().revision + 1;
		source.revisions.add(new PriceSourceRevisionValue(revision, encodeSafe(configuration)));
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
		Map<String, Object> configuration = decode(source.revisions.stream()
			.filter(revision -> revision.revision == source.candidateRevision.longValue())
			.findFirst()
			.orElseThrow().configurationJson);
		List<String> capabilities = assess(source.kind, configuration);
		boolean successful = capabilities.stream().noneMatch(value -> value.startsWith("failed:"));
		source.assessments.add(new PriceSourceAssessmentValue(UUID.randomUUID(), source.candidateRevision, successful,
				String.join("\n", capabilities), started, this.clock.instant()));
		source.registrationRevision++;
	}

	public void promote(UUID id, long expectedRegistrationRevision, long revision) {
		PriceSourceEntity source = source(id);
		requireRevision(source, expectedRegistrationRevision);
		if (source.candidateRevision == null || source.candidateRevision != revision) {
			throw new PriceSourceConflictException("PRICE_SOURCE_CANDIDATE_CHANGED",
					"The requested revision is not the current candidate");
		}
		boolean successful = source.assessments.stream()
			.anyMatch(assessment -> assessment.revision == revision && assessment.successful);
		if (!successful) {
			throw new PriceSourceValidationException("PRICE_SOURCE_ASSESSMENT_REQUIRED",
					"Promotion requires a successful assessment of the candidate revision");
		}
		source.activeRevision = revision;
		source.candidateRevision = null;
		source.registrationRevision++;
	}

	public PriceSourceBindingView bind(String bindingKey, UUID sourceId, long sourceRevision,
			Duration maximumObservationAge, Long expectedBindingRevision) {
		if (bindingKey == null || !BINDING_KEY.matcher(bindingKey).matches()) {
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
		PriceSourceBindingEntity binding = this.entities.find(PriceSourceBindingEntity.class, bindingKey);
		if (binding == null) {
			if (expectedBindingRevision != null) {
				throw new PriceSourceConflictException("PRICE_SOURCE_BINDING_REVISION_CONFLICT",
						"The Price Source binding changed; reload it and retry");
			}
			binding = new PriceSourceBindingEntity(bindingKey, 1, sourceId, sourceRevision, maximumObservationAge);
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
					.map(value -> new PriceSourceRevisionView(value.revision, decode(value.configurationJson)))
					.toList(),
				source.assessments.stream()
					.map(value -> new PriceSourceAssessmentView(value.id, value.revision, value.successful,
							List.of(value.capabilityResults.split("\\n")), value.observedFrom, value.observedUntil))
					.toList());
	}

	private static List<String> assess(String kind, Map<String, Object> configuration) {
		List<String> results = new ArrayList<>();
		if ("operator-schedule".equals(kind)) {
			Object rates = configuration.get("rates");
			results.add(rates instanceof List<?> values && !values.isEmpty()
					&& values.stream().allMatch(PriceSourceRegistry::validRate) ? "passed:rates"
							: "failed:rates-required");
		}
		else {
			Object endpoint = configuration.get("endpoint");
			results
				.add(endpoint instanceof String value && (value.startsWith("https://") || value.startsWith("http://"))
						? "passed:endpoint" : "failed:endpoint-required");
		}
		Object capabilities = configuration.get("capabilities");
		results.add(capabilities instanceof List<?> values && !values.isEmpty() ? "passed:capabilities"
				: "failed:capabilities-required");
		return results;
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

	private static void requireKind(String kind) {
		if (!Set.of("operator-schedule", "provider-api", "skypilot-catalog").contains(kind)) {
			throw new PriceSourceValidationException("PRICE_SOURCE_KIND_INVALID", "Price Source kind is not supported");
		}
	}

	private static String encodeSafe(Map<String, Object> configuration) {
		if (configuration == null || configuration.isEmpty()) {
			throw new PriceSourceValidationException("PRICE_SOURCE_CONFIGURATION_INVALID",
					"Configuration must not be empty");
		}
		requireNoSecrets(configuration);
		try {
			return JSON.writeValueAsString(configuration);
		}
		catch (JacksonException error) {
			throw new PriceSourceValidationException("PRICE_SOURCE_CONFIGURATION_INVALID",
					"Configuration must be valid JSON");
		}
	}

	private static void requireNoSecrets(Object value) {
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT).replace("_", "");
				if (SECRET_KEYS.contains(key) || key.contains("secret") || key.contains("password")
						|| key.contains("token")) {
					throw new PriceSourceValidationException("PRICE_SOURCE_SECRET_FORBIDDEN",
							"Configuration must contain only non-secret values");
				}
				requireNoSecrets(entry.getValue());
			}
		}
		else if (value instanceof Iterable<?> values) {
			values.forEach(PriceSourceRegistry::requireNoSecrets);
		}
		else if (value instanceof String text && text.matches("(?i)https?://.*")) {
			try {
				var uri = java.net.URI.create(text);
				if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
					throw new PriceSourceValidationException("PRICE_SOURCE_SECRET_FORBIDDEN",
							"Configuration must contain only non-secret values");
				}
			}
			catch (IllegalArgumentException error) {
				throw new PriceSourceValidationException("PRICE_SOURCE_CONFIGURATION_INVALID",
						"Configuration contains an invalid endpoint");
			}
		}
	}

	private static Map<String, Object> decode(String value) {
		try {
			return JSON.readValue(value, new TypeReference<>() {
			});
		}
		catch (JacksonException error) {
			throw new IllegalStateException("Persisted Price Source configuration is invalid", error);
		}
	}

}
