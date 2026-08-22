package de.zorro909.skywright.backend.rundefinition;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict JSON codec shared by schema-generated Run Definition models. */
final class RunDefinitionCodec {

	private static final int MAXIMUM_PORTABLE_DECIMAL_SCALE = 1_000_000_000;

	private static final int MAXIMUM_PORTABLE_NUMBER_LENGTH = 4_000;

	private static final int MAXIMUM_PORTABLE_NESTING_DEPTH = 256;

	private static final JsonMapper JSON = JsonMapper
		.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder()
				.maxNestingDepth(MAXIMUM_PORTABLE_NESTING_DEPTH)
				.maxNumberLength(MAXIMUM_PORTABLE_NUMBER_LENGTH)
				.build())
			.build())
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private static final Schema SCHEMA = SchemaRegistry
		.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
				builder -> builder
					.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))
		.getSchema(schema());

	private RunDefinitionCodec() {
	}

	static ObjectNode decode(String document) {
		JsonNode parsed;
		try {
			parsed = JSON.readTree(document);
		}
		catch (JacksonException | NumberFormatException error) {
			throw failure("RUN_DEFINITION_INVALID_JSON", "", "parse");
		}
		if (parsed == null || parsed.isMissingNode()) {
			throw failure("RUN_DEFINITION_INVALID_JSON", "", "parse");
		}
		if (parsed.isObject() && parsed.path("schemaVersion").isIntegralNumber()
				&& !parsed.path("schemaVersion").bigIntegerValue().equals(BigInteger.ONE)) {
			throw failure("RUN_DEFINITION_SCHEMA_VERSION_UNSUPPORTED", "/schemaVersion", "const");
		}
		if (parsed.isObject() && parsed.path("schemaVersion").isFloatingPointNumber()) {
			throw failure("RUN_DEFINITION_SCHEMA_VALIDATION", "/schemaVersion", "type");
		}
		List<RunDefinitionFailure> failures = new ArrayList<>(SCHEMA.validate(parsed)
			.stream()
			.map(error -> new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					error.getInstanceLocation().toString(), error.getKeyword()))
			.toList());
		validatePortableDecimals(parsed, "", failures);
		validateProjectVersionRelationships(parsed, failures);
		validateTargetRelationships(parsed, failures);
		validateStorageEndpoints(parsed, failures);
		if (!failures.isEmpty()) {
			throw new RunDefinitionValidationException(failures);
		}
		return ((ObjectNode) parsed).deepCopy();
	}

	private static void validateProjectVersionRelationships(JsonNode definition, List<RunDefinitionFailure> failures) {
		JsonNode version = definition.path("trainingProjectVersion");
		if (!version.isObject()) {
			return;
		}
		String expectedLabel = version.path("sourceRevision").asText() + "-" + version.path("pipeline").asText();
		if (!expectedLabel.equals(version.path("versionLabel").asText())) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					"/trainingProjectVersion/versionLabel", "projectVersionRelationship"));
		}
		if (version.path("images").isObject() && version.path("environmentProfiles").isObject()
				&& !fieldNames(version.path("images")).equals(fieldNames(version.path("environmentProfiles")))) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					"/trainingProjectVersion/environmentProfiles", "projectVersionRelationship"));
		}
	}

	private static List<String> fieldNames(JsonNode value) {
		List<String> names = new ArrayList<>();
		value.propertyStream().map(Map.Entry::getKey).sorted().forEach(names::add);
		return names;
	}

	static boolean hasPortableDecimal(BigDecimal value) {
		return Math.abs((long) value.scale()) <= MAXIMUM_PORTABLE_DECIMAL_SCALE;
	}

	private static void validatePortableDecimals(JsonNode value, String pointer, List<RunDefinitionFailure> failures) {
		if (value.isFloatingPointNumber() && !hasPortableDecimal(value.decimalValue())) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition", pointer,
					"portableDecimal"));
		}
		if (value.isArray()) {
			for (int index = 0; index < value.size(); index++) {
				validatePortableDecimals(value.get(index), pointer + "/" + index, failures);
			}
		}
		if (value.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				validatePortableDecimals(field.getValue(), pointer + "/" + escape(field.getKey()), failures);
			}
		}
	}

	private static void validateTargetRelationships(JsonNode definition, List<RunDefinitionFailure> failures) {
		if (!definition.isObject()) {
			return;
		}
		JsonNode target = definition.path("targetRequest");
		if (!target.isObject()) {
			return;
		}
		String targetClass = target.path("targetClass").asText();
		String requiredPurchaseMode = switch (targetClass) {
			case "local-single-gpu", "local-multi-gpu" -> "local";
			case "cloud-on-demand" -> "on-demand";
			case "cloud-spot" -> "spot";
			default -> null;
		};
		if (requiredPurchaseMode != null && !requiredPurchaseMode.equals(target.path("purchaseMode").asText())) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					"/targetRequest/purchaseMode", "targetRelationship"));
		}
		if ("local-single-gpu".equals(targetClass) && target.path("gpuCount").canConvertToInt()
				&& target.path("gpuCount").intValue() != 1) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					"/targetRequest/gpuCount", "targetRelationship"));
		}
		if ("local-multi-gpu".equals(targetClass) && target.path("gpuCount").canConvertToInt()
				&& target.path("gpuCount").intValue() < 2) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					"/targetRequest/gpuCount", "targetRelationship"));
		}
	}

	private static String escape(String token) {
		return token.replace("~", "~0").replace("/", "~1");
	}

	private static void validateStorageEndpoints(JsonNode definition, List<RunDefinitionFailure> failures) {
		validateStorageEndpoint(definition.at("/storage/execution/endpoint"), "/storage/execution/endpoint", failures);
		validateStorageEndpoint(definition.at("/storage/repatriation/destination/endpoint"),
				"/storage/repatriation/destination/endpoint", failures);
	}

	private static void validateStorageEndpoint(JsonNode value, String pointer, List<RunDefinitionFailure> failures) {
		if (!value.isTextual()) {
			return;
		}
		boolean valid;
		try {
			URI endpoint = URI.create(value.asText());
			valid = ("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
					&& endpoint.getHost() != null && endpoint.getUserInfo() == null && endpoint.getQuery() == null
					&& endpoint.getFragment() == null && value.asText().length() <= 2048;
		}
		catch (IllegalArgumentException error) {
			valid = false;
		}
		if (!valid) {
			failures.add(new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition", pointer,
					"storageEndpoint"));
		}
	}

	private static RunDefinitionValidationException failure(String code, String pointer, String keyword) {
		return new RunDefinitionValidationException(
				List.of(new RunDefinitionFailure(code, "run-definition", pointer, keyword)));
	}

	private static String schema() {
		try (InputStream stream = RunDefinitionCodec.class
			.getResourceAsStream("/META-INF/skywright/run-definition/schema.json")) {
			if (stream == null) {
				throw new IllegalStateException("Run Definition schema is not packaged");
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException error) {
			throw new IllegalStateException("Run Definition schema cannot be read", error);
		}
	}

}
