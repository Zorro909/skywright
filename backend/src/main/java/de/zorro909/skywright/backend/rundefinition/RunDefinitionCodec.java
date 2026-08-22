package de.zorro909.skywright.backend.rundefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict JSON codec shared by schema-generated Run Definition models. */
final class RunDefinitionCodec {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
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
		catch (JacksonException error) {
			throw failure("RUN_DEFINITION_INVALID_JSON", "", "parse");
		}
		if (parsed.isObject() && parsed.path("schemaVersion").isIntegralNumber()
				&& parsed.path("schemaVersion").asInt() != 1) {
			throw failure("RUN_DEFINITION_SCHEMA_VERSION_UNSUPPORTED", "/schemaVersion", "const");
		}
		if (parsed.isObject() && parsed.path("schemaVersion").isFloatingPointNumber()) {
			throw failure("RUN_DEFINITION_SCHEMA_VALIDATION", "/schemaVersion", "type");
		}
		List<RunDefinitionFailure> failures = SCHEMA.validate(parsed)
			.stream()
			.map(error -> new RunDefinitionFailure("RUN_DEFINITION_SCHEMA_VALIDATION", "run-definition",
					error.getInstanceLocation().toString(), error.getKeyword()))
			.toList();
		if (!failures.isEmpty()) {
			throw new RunDefinitionValidationException(failures);
		}
		return ((ObjectNode) parsed).deepCopy();
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
