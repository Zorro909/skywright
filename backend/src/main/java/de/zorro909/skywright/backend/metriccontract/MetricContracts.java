package de.zorro909.skywright.backend.metriccontract;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Independently validates and composes version-pinned metric artifacts. */
public final class MetricContracts {

	private static final Pattern DUPLICATE = Pattern.compile("(?i)duplicate.*?['\"]([^'\"]+)['\"]");

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.build();

	private final ObjectNode manifest;

	private final ObjectNode schema;

	private final Set<String> units;

	private final List<MetricDefinition> systemDefinitions;

	private final Schema contractSchema;

	public MetricContracts() {
		this.manifest = parseObject(resource("manifest.json"), "metric-manifest");
		this.schema = parseObject(resource("schema.json"), "skywright-schema");
		String actualDigest = "sha256:" + sha256(canonical(this.schema));
		if (!actualDigest.equals(this.manifest.path("schemaDigest").asText())) {
			throw new IllegalStateException("packaged Skywright Metric Schema digest does not match its manifest");
		}
		this.units = this.schema.path("units")
			.valueStream()
			.map(unit -> unit.path("name").asText())
			.collect(Collectors.toUnmodifiableSet());
		this.systemDefinitions = this.schema.path("definitions").valueStream().map(this::definition).toList();
		this.contractSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(parseObject(resource("project-contract.schema.json"), "project-contract-schema"));
	}

	public MetricSchemaIdentity skywrightSchemaIdentity() {
		return new MetricSchemaIdentity(this.manifest.path("schemaVersion").asText(),
				this.manifest.path("schemaDigest").asText());
	}

	public String skywrightSchemaIdentityJson() {
		MetricSchemaIdentity identity = skywrightSchemaIdentity();
		return "{\"version\":" + quote(identity.version()) + ",\"digest\":" + quote(identity.digest()) + "}";
	}

	public MetricContract compile(String artifactJson) {
		ObjectNode artifact = parseObject(artifactJson, "project-contract");
		List<MetricError> errors = this.contractSchema.validate(artifact)
			.stream()
			.map(error -> new MetricError("METRIC_SCHEMA_VALIDATION", "project-contract",
					error.getInstanceLocation().toString(), error.getKeyword()))
			.collect(Collectors.toCollection(ArrayList::new));
		if (!parse(skywrightSchemaIdentityJson(), "skywright-schema").equals(artifact.get("skywrightSchema"))) {
			errors.add(new MetricError("METRIC_SCHEMA_IDENTITY_MISMATCH", "project-contract", "/skywrightSchema",
					"const"));
		}
		errors.addAll(semanticErrors(artifact));
		if (!errors.isEmpty()) {
			throw new MetricContractException(errors);
		}
		String canonicalJson = canonical(artifact);
		return new MetricContract(this, artifact, canonicalJson, "sha256:" + sha256(canonicalJson));
	}

	public MetricContractAssessment assess(String artifactJson, String expectedDigest, String projectIdentity,
			String projectVersion) {
		try {
			MetricContract contract = compile(artifactJson);
			if (!contract.digest().equals(expectedDigest)) {
				throw new MetricContractException(
						List.of(new MetricError("METRIC_CONTRACT_DIGEST_MISMATCH", "project-contract", "", "digest")));
			}
			MetricCatalog catalog = contract.catalog(projectIdentity, projectVersion);
			return new MetricContractAssessment(true, catalog, List.of());
		}
		catch (MetricContractException failure) {
			return new MetricContractAssessment(false, null, failure.errors());
		}
	}

	public boolean projectMetricsComparable(MetricCatalog left, MetricCatalog right, String name) {
		if (!left.projectIdentity().equals(right.projectIdentity())) {
			return false;
		}
		MetricDefinition leftDefinition = definitionNamed(left.projectDefinitions(), name);
		MetricDefinition rightDefinition = definitionNamed(right.projectDefinitions(), name);
		return leftDefinition != null && rightDefinition != null && leftDefinition.name().equals(rightDefinition.name())
				&& leftDefinition.numericKind().equals(rightDefinition.numericKind())
				&& leftDefinition.unit().equals(rightDefinition.unit())
				&& leftDefinition.recordingBasis().equals(rightDefinition.recordingBasis())
				&& leftDefinition.comparison().equals(rightDefinition.comparison())
				&& numbersEqual(leftDefinition.minimum(), rightDefinition.minimum())
				&& numbersEqual(leftDefinition.maximum(), rightDefinition.maximum())
				&& java.util.Objects.equals(leftDefinition.stepReduction(), rightDefinition.stepReduction());
	}

	MetricCatalog catalog(ObjectNode artifact, String digest, String projectIdentity, String projectVersion) {
		List<MetricDefinition> definitions = artifact.path("definitions").valueStream().map(this::definition).toList();
		return new MetricCatalog(projectIdentity, projectVersion, digest, skywrightSchemaIdentity(), this.units,
				definitions, this.systemDefinitions);
	}

	private List<MetricError> semanticErrors(ObjectNode artifact) {
		List<MetricError> errors = new ArrayList<>();
		Set<String> names = new java.util.HashSet<>();
		int index = 0;
		for (JsonNode definition : artifact.path("definitions")) {
			String base = "/definitions/" + index;
			String name = definition.path("name").asText();
			if (name.startsWith("skywright/")) {
				errors.add(new MetricError("METRIC_RESERVED_NAME", "project-contract", base + "/name", "pattern"));
			}
			if (!name.isEmpty() && !names.add(name)) {
				errors
					.add(new MetricError("METRIC_DEFINITION_COLLISION", "project-contract", base + "/name", "unique"));
			}
			String unit = definition.path("unit").asText();
			if (!unit.isEmpty() && !this.units.contains(unit)) {
				errors.add(new MetricError("METRIC_UNKNOWN_UNIT", "project-contract", base + "/unit", "registry"));
			}
			if ("integer".equals(definition.path("numericKind").asText())
					&& "mean".equals(definition.path("stepReduction").asText())) {
				errors.add(new MetricError("METRIC_INTEGER_MEAN", "project-contract", base + "/stepReduction",
						"semantic"));
			}
			JsonNode bounds = definition.get("bounds");
			if (bounds != null && bounds.isObject() && bounds.has("minimum") && bounds.has("maximum")
					&& bounds.path("minimum").decimalValue().compareTo(bounds.path("maximum").decimalValue()) > 0) {
				errors.add(new MetricError("METRIC_BOUNDS_ORDER", "project-contract", base + "/bounds", "semantic"));
			}
			index++;
		}
		return errors;
	}

	private MetricDefinition definition(JsonNode value) {
		JsonNode bounds = value.path("bounds");
		return new MetricDefinition(value.path("name").asText(), value.path("numericKind").asText(),
				value.path("unit").asText(), value.path("recordingBasis").asText(), value.path("comparison").asText(),
				value.has("stepReduction") ? value.path("stepReduction").asText() : null,
				decimalOrNull(bounds.get("minimum")), decimalOrNull(bounds.get("maximum")),
				textOrNull(value.get("displayName")), textOrNull(value.get("description")));
	}

	private static MetricDefinition definitionNamed(List<MetricDefinition> definitions, String name) {
		return definitions.stream().filter(definition -> definition.name().equals(name)).findFirst().orElse(null);
	}

	private static boolean numbersEqual(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	private static BigDecimal decimalOrNull(JsonNode value) {
		return value == null ? null : value.decimalValue();
	}

	private static String textOrNull(JsonNode value) {
		return value == null ? null : value.asText();
	}

	private static ObjectNode parseObject(String source, String layer) {
		JsonNode parsed = parse(source, layer);
		if (!parsed.isObject()) {
			throw new MetricContractException(List.of(new MetricError("METRIC_SCHEMA_VALIDATION", layer, "", "type")));
		}
		return (ObjectNode) parsed;
	}

	private static JsonNode parse(String source, String layer) {
		try {
			return JSON.readTree(source);
		}
		catch (JacksonException error) {
			Matcher duplicate = DUPLICATE.matcher(error.getOriginalMessage());
			if (duplicate.find()) {
				throw new MetricContractException(List.of(new MetricError("METRIC_DUPLICATE_PROPERTY", layer,
						"/" + duplicate.group(1).replace("~", "~0").replace("/", "~1"), "parse")));
			}
			throw new MetricContractException(List.of(new MetricError("METRIC_INVALID_JSON", layer, "", "parse")));
		}
	}

	private static String canonical(JsonNode value) {
		if (value.isObject()) {
			return "{" + value.propertyStream()
				.sorted(java.util.Map.Entry.comparingByKey())
				.map(entry -> quote(entry.getKey()) + ":" + canonical(entry.getValue()))
				.collect(Collectors.joining(",")) + "}";
		}
		if (value.isArray()) {
			return "[" + value.valueStream().map(MetricContracts::canonical).collect(Collectors.joining(",")) + "]";
		}
		if (value.isNumber()) {
			return value.decimalValue().toPlainString();
		}
		return value.toString();
	}

	private static String quote(String value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (JacksonException error) {
			throw new IllegalArgumentException("cannot serialize metric contract string", error);
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	private static String resource(String name) {
		try (InputStream stream = MetricContracts.class.getResourceAsStream("/META-INF/skywright/metrics/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("missing metric resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException error) {
			throw new IllegalStateException("cannot read metric resource " + name, error);
		}
	}

}
