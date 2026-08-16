package de.zorro909.skywright.backend.configurationcontract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Compiles version-pinned Project Configuration Contracts with a trusted schema. */
public final class ConfigurationContracts {

	private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

	private static final String SCHEMA_VERSION = "0.1.0";

	private static final Pattern DUPLICATE = Pattern.compile("(?i)duplicate.*?['\"]([^'\"]+)['\"]");

	private static final Set<String> VOCABULARIES = Set.of("https://json-schema.org/draft/2020-12/vocab/core",
			"https://json-schema.org/draft/2020-12/vocab/applicator",
			"https://json-schema.org/draft/2020-12/vocab/unevaluated",
			"https://json-schema.org/draft/2020-12/vocab/validation",
			"https://json-schema.org/draft/2020-12/vocab/meta-data",
			"https://json-schema.org/draft/2020-12/vocab/format-annotation",
			"https://json-schema.org/draft/2020-12/vocab/format-assertion",
			"https://json-schema.org/draft/2020-12/vocab/content");

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private final String schemaJson;

	private final ObjectNode librarySchema;

	private final ObjectNode libraryDefaults;

	public ConfigurationContracts() {
		this.schemaJson = resource("schema.json");
		this.librarySchema = parseObject(this.schemaJson, "skywright-schema");
		this.libraryDefaults = parseObject(resource("defaults.json"), "library-defaults");
	}

	public String skywrightSchemaIdentityJson() {
		return "{\"version\":\"" + SCHEMA_VERSION + "\",\"digest\":\"sha256:" + sha256(this.schemaJson) + "\"}";
	}

	public ConfigurationContract compile(String artifactJson) {
		ObjectNode artifact = parseObject(artifactJson, "project-contract");
		if (artifact.path("contractVersion").asInt(-1) != 1) {
			throw failure("CONFIG_CONTRACT_VERSION", "project-contract", "/contractVersion", "const");
		}
		JsonNode expectedIdentity = parse(skywrightSchemaIdentityJson(), "skywright-schema");
		if (!expectedIdentity.equals(artifact.get("skywrightSchema"))) {
			throw failure("CONFIG_SCHEMA_IDENTITY_MISMATCH", "project-contract", "/skywrightSchema", "const");
		}
		ObjectNode projectSchema = requireObject(artifact.get("projectSchema"), "project-contract", "/projectSchema");
		ObjectNode defaults = requireObject(artifact.get("defaults"), "project-defaults", "");
		ObjectNode witness = requireObject(artifact.get("defaultsCompletionWitness"), "defaults-completion-witness",
				"");
		ObjectNode references = requireObject(artifact.get("references"), "project-contract", "/references");
		if (!DIALECT.equals(projectSchema.path("$schema").asText())) {
			throw failure("CONFIG_UNSUPPORTED_DIALECT", "project-contract", "/projectSchema/$schema", "$schema");
		}

		Set<String> bundled = references.propertyStream()
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toSet());
		List<ConfigurationError> featureErrors = new ArrayList<>();
		schemaFeatureErrors(projectSchema, bundled, List.of("projectSchema"), featureErrors);
		references.properties()
			.forEach(entry -> schemaFeatureErrors(entry.getValue(), bundled, List.of("references", entry.getKey()),
					featureErrors));
		if (!featureErrors.isEmpty()) {
			throw new ConfigurationContractException(featureErrors);
		}

		List<ConfigurationError> ownershipErrors = new ArrayList<>();
		ownershipErrors(this.librarySchema, projectSchema, List.of(), ownershipErrors);
		if (!ownershipErrors.isEmpty()) {
			throw new ConfigurationContractException(ownershipErrors);
		}
		ObjectNode composedSchema = this.librarySchema.deepCopy();
		mergeSchema(composedSchema, projectSchema);
		ObjectNode mergedDefaults = overlay(this.libraryDefaults, defaults);
		ObjectNode completed = fillAbsent(mergedDefaults, witness, List.of());

		Map<String, String> bundledSchemas = new LinkedHashMap<>();
		references.properties().forEach(entry -> {
			if (!entry.getKey().startsWith("urn:") || !entry.getKey().equals(entry.getValue().path("$id").asText())) {
				throw failure("CONFIG_INVALID_BUNDLED_REFERENCE", "project-contract",
						pointer(List.of("references", entry.getKey(), "$id")), "$id");
			}
			bundledSchemas.put(entry.getKey(), entry.getValue().toString());
		});
		SchemaRegistryConfig config = SchemaRegistryConfig.builder()
			.formatAssertionsEnabled(true)
			.pathType(PathType.JSON_POINTER)
			.build();
		SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
				builder -> builder.schemaRegistryConfig(config).schemas(bundledSchemas));
		Schema schema = registry.getSchema(composedSchema);
		List<ConfigurationError> baselineErrors = schema.validate(completed)
			.stream()
			.map(error -> ConfigurationContract.schemaError(error, "defaults-completion-witness"))
			.sorted()
			.toList();
		if (!baselineErrors.isEmpty()) {
			throw new ConfigurationContractException(baselineErrors);
		}
		return new ConfigurationContract(this, schema, mergedDefaults);
	}

	ObjectNode parseObject(String source, String layer) {
		JsonNode parsed = parse(source, layer);
		if (!parsed.isObject()) {
			throw failure("CONFIG_LAYER_NOT_OBJECT", layer, "", "type");
		}
		return (ObjectNode) parsed;
	}

	private JsonNode parse(String source, String layer) {
		try {
			return JSON.readTree(source);
		}
		catch (JacksonException error) {
			Matcher duplicate = DUPLICATE.matcher(error.getOriginalMessage());
			if (duplicate.find()) {
				throw failure("CONFIG_DUPLICATE_PROPERTY", layer, pointer(List.of(duplicate.group(1))), "parse");
			}
			throw failure("CONFIG_INVALID_JSON", layer, "", "parse");
		}
	}

	static ObjectNode overlay(ObjectNode lower, ObjectNode higher) {
		ObjectNode result = lower.deepCopy();
		higher.properties().forEach(entry -> {
			JsonNode existing = result.get(entry.getKey());
			if (existing != null && existing.isObject() && entry.getValue().isObject()) {
				result.set(entry.getKey(), overlay((ObjectNode) existing, (ObjectNode) entry.getValue()));
			}
			else {
				result.set(entry.getKey(), entry.getValue().deepCopy());
			}
		});
		return result;
	}

	private static ObjectNode fillAbsent(ObjectNode existing, ObjectNode witness, List<String> path) {
		ObjectNode result = existing.deepCopy();
		List<ConfigurationError> errors = new ArrayList<>();
		witness.properties().forEach(entry -> {
			List<String> propertyPath = append(path, entry.getKey());
			JsonNode current = existing.get(entry.getKey());
			if (current == null) {
				result.set(entry.getKey(), entry.getValue().deepCopy());
			}
			else if (current.isObject() && entry.getValue().isObject()) {
				try {
					result.set(entry.getKey(),
							fillAbsent((ObjectNode) current, (ObjectNode) entry.getValue(), propertyPath));
				}
				catch (ConfigurationContractException error) {
					errors.addAll(error.errors());
				}
			}
			else {
				errors.add(new ConfigurationError("CONFIG_WITNESS_REPLACEMENT", "defaults-completion-witness",
						pointer(propertyPath), "overlay"));
			}
		});
		if (!errors.isEmpty()) {
			throw new ConfigurationContractException(errors);
		}
		return result;
	}

	private static void ownershipErrors(ObjectNode library, ObjectNode project, List<String> path,
			List<ConfigurationError> errors) {
		JsonNode libraryProperties = library.path("properties");
		JsonNode projectProperties = project.path("properties");
		if (!libraryProperties.isObject() || !projectProperties.isObject()) {
			return;
		}
		projectProperties.properties().forEach(entry -> {
			JsonNode libraryDefinition = libraryProperties.get(entry.getKey());
			if (libraryDefinition == null) {
				return;
			}
			List<String> propertyPath = append(path, entry.getKey());
			JsonNode projectDefinition = entry.getValue();
			if (libraryDefinition.isObject() && projectDefinition.isObject()
					&& "object".equals(libraryDefinition.path("type").asText())
					&& "object".equals(projectDefinition.path("type").asText())
					&& projectDefinition.path("properties").isObject()) {
				ownershipErrors((ObjectNode) libraryDefinition, (ObjectNode) projectDefinition, propertyPath, errors);
				return;
			}
			errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema", pointer(propertyPath),
					"properties"));
		});
	}

	private static void mergeSchema(ObjectNode library, ObjectNode project) {
		ObjectNode libraryProperties = (ObjectNode) library.path("properties");
		project.path("properties").properties().forEach(entry -> {
			JsonNode existing = libraryProperties.get(entry.getKey());
			if (existing == null) {
				libraryProperties.set(entry.getKey(), entry.getValue().deepCopy());
			}
			else {
				mergeSchema((ObjectNode) existing, (ObjectNode) entry.getValue());
				mergeRequired((ObjectNode) existing, entry.getValue().path("required"));
			}
		});
		mergeRequired(library, project.path("required"));
		for (String keyword : List.of("$defs", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
				"dependentRequired", "dependentSchemas")) {
			if (project.has(keyword)) {
				library.set(keyword, project.get(keyword).deepCopy());
			}
		}
	}

	private static void mergeRequired(ObjectNode target, JsonNode additions) {
		if (!additions.isArray()) {
			return;
		}
		ArrayNode required = target.withArray("required");
		additions.forEach(item -> {
			if (required.valueStream().noneMatch(item::equals)) {
				required.add(item.deepCopy());
			}
		});
	}

	private static void schemaFeatureErrors(JsonNode value, Set<String> bundled, List<String> path,
			List<ConfigurationError> errors) {
		if (value.isObject()) {
			if (value.has("$schema") && !DIALECT.equals(value.path("$schema").asText())) {
				errors.add(new ConfigurationError("CONFIG_UNSUPPORTED_DIALECT", "project-contract",
						pointer(append(path, "$schema")), "$schema"));
			}
			JsonNode vocabulary = value.path("$vocabulary");
			if (vocabulary.isObject()) {
				vocabulary.properties().forEach(entry -> {
					if (entry.getValue().asBoolean(false) && !VOCABULARIES.contains(entry.getKey())) {
						errors.add(new ConfigurationError("CONFIG_UNSUPPORTED_VOCABULARY", "project-contract",
								pointer(append(append(path, "$vocabulary"), entry.getKey())), "$vocabulary"));
					}
				});
			}
			JsonNode reference = value.get("$ref");
			if (reference != null && reference.isTextual() && !reference.asText().startsWith("#")
					&& !bundled.contains(reference.asText())) {
				errors.add(new ConfigurationError("CONFIG_MUTABLE_EXTERNAL_REFERENCE", "project-contract",
						pointer(append(path, "$ref")), "$ref"));
			}
			value.properties()
				.forEach(entry -> schemaFeatureErrors(entry.getValue(), bundled, append(path, entry.getKey()), errors));
		}
		else if (value.isArray()) {
			int index = 0;
			for (JsonNode child : value) {
				schemaFeatureErrors(child, bundled, append(path, Integer.toString(index++)), errors);
			}
		}
	}

	private static ObjectNode requireObject(JsonNode value, String source, String pointer) {
		if (value == null || !value.isObject()) {
			throw failure("CONFIG_LAYER_NOT_OBJECT", source, pointer, "type");
		}
		return (ObjectNode) value;
	}

	private static ConfigurationContractException failure(String code, String source, String pointer, String keyword) {
		return new ConfigurationContractException(List.of(new ConfigurationError(code, source, pointer, keyword)));
	}

	private static List<String> append(List<String> path, String element) {
		List<String> result = new ArrayList<>(path);
		result.add(element);
		return result;
	}

	private static String pointer(List<String> parts) {
		return parts.stream().map(part -> "/" + part.replace("~", "~0").replace("/", "~1")).reduce("", String::concat);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private static String resource(String name) {
		try (InputStream resource = ConfigurationContracts.class
			.getResourceAsStream("/META-INF/skywright/configuration/" + name)) {
			if (resource == null) {
				throw new IllegalStateException("Missing configuration resource " + name);
			}
			return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}

}
