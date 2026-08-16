package de.zorro909.skywright.backend.configurationcontract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
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

	private static final List<String> APPLICATOR_KEYWORDS = List.of("allOf", "anyOf", "oneOf", "not", "if", "then",
			"else", "dependentSchemas");

	private static final Set<String> SHARED_OBJECT_KEYWORDS = Set.of("$comment", "$defs", "$schema",
			"additionalProperties", "description", "properties", "required", "title", "type", "allOf", "anyOf", "oneOf",
			"not", "if", "then", "else", "dependentRequired", "dependentSchemas");

	private static final Set<String> SHARED_APPLICATOR_COLLISIONS = Set.of("additionalProperties", "const", "enum",
			"maxProperties", "minProperties", "patternProperties", "propertyNames", "unevaluatedProperties");

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
		Schema artifactSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(parseObject(resource("project-contract.schema.json"), "project-contract-schema"));
		validate(artifactSchema, artifact, "project-contract");
		if (!DIALECT.equals(projectSchema.path("$schema").asText())) {
			throw failure("CONFIG_UNSUPPORTED_DIALECT", "project-contract", "/projectSchema/$schema", "$schema");
		}
		validateProjectSchema(projectSchema);

		Set<String> bundled = references.propertyStream()
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toSet());
		List<ConfigurationError> featureErrors = new ArrayList<>();
		schemaFeatureErrors(projectSchema, bundled, List.of("projectSchema"), featureErrors);
		references.properties().forEach(entry -> {
			if (entry.getValue().isObject()) {
				validateProjectSchema((ObjectNode) entry.getValue());
			}
			schemaFeatureErrors(entry.getValue(), bundled, List.of("references", entry.getKey()), featureErrors);
		});
		if (!featureErrors.isEmpty()) {
			throw new ConfigurationContractException(featureErrors);
		}

		List<ConfigurationError> ownershipErrors = new ArrayList<>();
		ownershipErrors(this.librarySchema, projectSchema, List.of(), projectSchema, references, ownershipErrors);
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
		Schema schema;
		try {
			schema = registry.getSchema(composedSchema);
		}
		catch (RuntimeException error) {
			throw failure("CONFIG_INVALID_PROJECT_SCHEMA", "project-schema", "", "schema");
		}
		validate(schema, completed, "defaults-completion-witness");
		return new ConfigurationContract(this, schema, mergedDefaults);
	}

	void validate(Schema schema, JsonNode instance, String source) {
		List<ConfigurationError> errors = schema.validate(instance)
			.stream()
			.map(error -> new ConfigurationError("CONFIG_SCHEMA_VALIDATION", source,
					error.getInstanceLocation().toString(), error.getKeyword()))
			.sorted()
			.toList();
		if (!errors.isEmpty()) {
			throw new ConfigurationContractException(errors);
		}
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
			ObjectNode rootProject, ObjectNode references, List<ConfigurationError> errors) {
		JsonNode libraryProperties = library.path("properties");
		JsonNode projectProperties = project.path("properties");
		if (!libraryProperties.isObject()) {
			return;
		}
		project.propertyStream()
			.filter(entry -> !SHARED_OBJECT_KEYWORDS.contains(entry.getKey()))
			.forEach(entry -> errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema",
					pointer(path), entry.getKey())));
		JsonNode projectRequired = project.path("required");
		if (projectRequired.isArray()) {
			projectRequired.forEach(name -> {
				if (name.isTextual() && libraryProperties.has(name.asText())) {
					errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema",
							pointer(append(path, name.asText())), "required"));
				}
			});
		}
		if (projectProperties.isObject()) {
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
					ownershipErrors((ObjectNode) libraryDefinition, (ObjectNode) projectDefinition, propertyPath,
							rootProject, references, errors);
					return;
				}
				errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema", pointer(propertyPath),
						"properties"));
			});
		}
		for (String keyword : APPLICATOR_KEYWORDS) {
			if (project.has(keyword)) {
				JsonNode constraint = project.get(keyword);
				if ("dependentSchemas".equals(keyword) && constraint.isObject()) {
					constraint.forEach(schema -> applicatorOwnershipErrors((ObjectNode) libraryProperties, schema, path,
							rootProject, references, Set.of(), errors));
				}
				else {
					applicatorOwnershipErrors((ObjectNode) libraryProperties, constraint, path, rootProject, references,
							Set.of(), errors);
				}
			}
		}
	}

	private static void applicatorOwnershipErrors(ObjectNode libraryProperties, JsonNode constraint, List<String> path,
			ObjectNode rootProject, ObjectNode references, Set<String> visitedReferences,
			List<ConfigurationError> errors) {
		if (constraint.isArray()) {
			constraint.forEach(item -> applicatorOwnershipErrors(libraryProperties, item, path, rootProject, references,
					visitedReferences, errors));
			return;
		}
		if (!constraint.isObject()) {
			return;
		}
		JsonNode referencedSchema = referencedSchema(rootProject, references, constraint.get("$ref"));
		String referenceKey = constraint.path("$ref").asText() + "|" + pointer(path);
		if (referencedSchema != null) {
			if (!visitedReferences.contains(referenceKey)) {
				Set<String> nextVisited = new HashSet<>(visitedReferences);
				nextVisited.add(referenceKey);
				applicatorOwnershipErrors(libraryProperties, referencedSchema, path, rootProject, references,
						nextVisited, errors);
			}
			else {
				errors
					.add(new ConfigurationError("CONFIG_RECURSIVE_REFERENCE", "project-schema", pointer(path), "$ref"));
			}
		}
		else if (constraint.has("$ref") || constraint.has("$dynamicRef")) {
			errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema", pointer(path),
					constraint.has("$ref") ? "$ref" : "$dynamicRef"));
		}
		JsonNode constrainedProperties = constraint.path("properties");
		SHARED_APPLICATOR_COLLISIONS.stream()
			.filter(constraint::has)
			.forEach(keyword -> errors
				.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema", pointer(path), keyword)));
		if (constrainedProperties.isObject()) {
			constrainedProperties.properties().forEach(entry -> {
				JsonNode libraryDefinition = libraryProperties.get(entry.getKey());
				if (libraryDefinition == null) {
					return;
				}
				List<String> propertyPath = append(path, entry.getKey());
				if (libraryDefinition.path("properties").isObject() && entry.getValue().path("properties").isObject()) {
					applicatorOwnershipErrors((ObjectNode) libraryDefinition.path("properties"), entry.getValue(),
							propertyPath, rootProject, references, visitedReferences, errors);
				}
				else {
					errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema",
							pointer(propertyPath), "properties"));
				}
			});
		}
		JsonNode required = constraint.path("required");
		if (required.isArray()) {
			required.forEach(name -> {
				if (name.isTextual() && libraryProperties.has(name.asText())) {
					errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema",
							pointer(append(path, name.asText())), "required"));
				}
			});
		}
		JsonNode dependentRequired = constraint.path("dependentRequired");
		if (dependentRequired.isObject()) {
			dependentRequired.properties().forEach(entry -> {
				boolean constrainsLibrary = libraryProperties.has(entry.getKey());
				if (entry.getValue().isArray()) {
					constrainsLibrary = constrainsLibrary || entry.getValue()
						.valueStream()
						.anyMatch(item -> item.isTextual() && libraryProperties.has(item.asText()));
				}
				if (constrainsLibrary) {
					errors.add(new ConfigurationError("CONFIG_OWNERSHIP_COLLISION", "project-schema",
							pointer(append(path, entry.getKey())), "dependentRequired"));
				}
			});
		}
		for (String keyword : APPLICATOR_KEYWORDS) {
			if (constraint.has(keyword)) {
				JsonNode nested = constraint.get(keyword);
				if ("dependentSchemas".equals(keyword) && nested.isObject()) {
					nested.forEach(schema -> applicatorOwnershipErrors(libraryProperties, schema, path, rootProject,
							references, visitedReferences, errors));
				}
				else {
					applicatorOwnershipErrors(libraryProperties, nested, path, rootProject, references,
							visitedReferences, errors);
				}
			}
		}
	}

	private static JsonNode referencedSchema(ObjectNode rootProject, ObjectNode references, JsonNode reference) {
		if (reference == null || !reference.isTextual()) {
			return null;
		}
		String[] parts = reference.asText().split("#", 2);
		JsonNode result = parts[0].isEmpty() ? rootProject : references.get(parts[0]);
		if (result == null) {
			return null;
		}
		if (parts.length == 2 && !parts[1].isEmpty()) {
			String fragment = URLDecoder.decode(parts[1].replace("+", "%2B"), StandardCharsets.UTF_8);
			if (!fragment.startsWith("/")) {
				return findAnchor(result, fragment);
			}
			result = result.at(fragment);
		}
		return result.isMissingNode() ? null : result;
	}

	private static JsonNode findAnchor(JsonNode value, String anchor) {
		if (value.isObject() && anchor.equals(value.path("$anchor").asText())) {
			return value;
		}
		if (value.isObject() || value.isArray()) {
			for (JsonNode child : value) {
				JsonNode found = findAnchor(child, anchor);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static void validateProjectSchema(ObjectNode projectSchema) {
		Schema metaSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(SchemaLocation.of(DIALECT));
		List<ConfigurationError> errors = metaSchema.validate(projectSchema)
			.stream()
			.map(error -> new ConfigurationError("CONFIG_INVALID_PROJECT_SCHEMA", "project-schema",
					error.getInstanceLocation().toString(), error.getKeyword()))
			.sorted()
			.toList();
		if (!errors.isEmpty()) {
			throw new ConfigurationContractException(errors);
		}
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
			for (String keyword : List.of("$ref", "$dynamicRef")) {
				JsonNode reference = value.get(keyword);
				if (reference != null && reference.isTextual() && !reference.asText().startsWith("#")) {
					String identity = reference.asText().split("#", 2)[0];
					if (!bundled.contains(identity)) {
						errors.add(new ConfigurationError("CONFIG_MUTABLE_EXTERNAL_REFERENCE", "project-contract",
								pointer(append(path, keyword)), keyword));
					}
				}
			}
			value.properties()
				.stream()
				.filter(entry -> !Set.of("const", "default", "examples").contains(entry.getKey()))
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
