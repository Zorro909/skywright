package de.zorro909.skywright.backend.configurationcontract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ConfigurationContractTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ConfigurationContracts contracts = new ConfigurationContracts();

	@Test
	void resolvesTheNormativeThreeLayerStructuralOverlay() {
		var artifact = """
				{
				  "contractVersion": 1,
				  "skywrightSchema": %s,
				  "projectSchema": {
				    "$schema": "https://json-schema.org/draft/2020-12/schema",
				    "type": "object",
				    "properties": {
				      "project": {
				        "type": "object",
				        "properties": {
				          "nested": {"type": "object"},
				          "array": {"type": "array"},
				          "replace": {},
				          "nullable": {"type": ["number", "null"]},
				          "large": {"type": "integer"},
				          "decimal": {"type": "number"}
				        },
				        "required": ["nested", "array", "replace", "nullable", "large", "decimal"],
				        "additionalProperties": false
				      }
				    }
				  },
				  "defaults": {
				    "project": {
				      "nested": {"left": 1, "overridden": 2},
				      "array": [1, 2],
				      "replace": {"old": true},
				      "nullable": 1,
				      "large": 9007199254740993,
				      "decimal": 0.1
				    }
				  },
				  "defaultsCompletionWitness": {},
				  "references": {}
				}
				""".formatted(contracts.skywrightSchemaIdentityJson());

		var contract = contracts.compile(artifact);
		var resolved = contract.resolve("""
				{"project":{"nested":{"overridden":3,"right":4},"array":[3],"replace":2,"nullable":null}}
				""");

		assertThat(resolved.toString()).isEqualTo(
				"""
						{"reproducibility":{"seed":0},"dataset":{"ordering":{"policy":"deterministic-shuffle","version":"feistel-sha256-v1"}},"checkpoint":{"cadence":100,"retention":3,"keepEveryNth":null},"metrics":{"flushInterval":10,"segmentRoll":1000,"systemSamplingInterval":10},"project":{"nested":{"left":1,"overridden":3,"right":4},"array":[3],"replace":2,"nullable":null,"large":9007199254740993,"decimal":0.1}}"""
					.trim());
	}

	@Test
	void reportsOwnershipAndWitnessFailuresWithStableDetails() {
		var collision = projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"reproducibility":{"type":"object","properties":{"seed":{"type":"integer"}}}}}
						""",
				"{}", "{}");
		assertThatExceptionOfType(ConfigurationContractException.class).isThrownBy(() -> contracts.compile(collision))
			.satisfies(error -> assertThat(error.errors()).containsExactly(new ConfigurationError(
					"CONFIG_OWNERSHIP_COLLISION", "project-schema", "/reproducibility/seed", "properties")));

		var witnessReplacement = projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"project":{"type":"integer"}}}
						""",
				"{\"project\":1}", "{\"project\":1}");
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> contracts.compile(witnessReplacement))
			.satisfies(error -> assertThat(error.errors()).containsExactly(new ConfigurationError(
					"CONFIG_WITNESS_REPLACEMENT", "defaults-completion-witness", "/project", "overlay")));
	}

	@Test
	void reportsSubmissionFailuresInDeterministicOrder() {
		var contract = contracts.compile(projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"project":{"type":"object","properties":{"count":{"type":"integer"},"name":{"type":"string"}},"required":["count","name"],"additionalProperties":false}},"required":["project"]}
						""",
				"{}", "{\"project\":{\"count\":1,\"name\":\"valid\"}}"));

		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> contract.resolve("{\"project\":{\"count\":\"wrong\"}}"))
			.satisfies(error -> assertThat(error.errors()).containsExactly(
					new ConfigurationError("CONFIG_SCHEMA_VALIDATION", "submission", "/project", "required"),
					new ConfigurationError("CONFIG_SCHEMA_VALIDATION", "submission", "/project/count", "type")));
	}

	@Test
	void rejectsDuplicatePropertiesAndNonObjectLayers() {
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> contracts.compile("{\"contractVersion\":1,\"contractVersion\":1}"))
			.satisfies(error -> assertThat(error.errors()).containsExactly(new ConfigurationError(
					"CONFIG_DUPLICATE_PROPERTY", "project-contract", "/contractVersion", "parse")));

		var contract = contracts.compile(projectContract("""
				{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{}}
				""", "{}", "{}"));
		assertThatExceptionOfType(ConfigurationContractException.class).isThrownBy(() -> contract.resolve("[]"))
			.satisfies(error -> assertThat(error.errors())
				.containsExactly(new ConfigurationError("CONFIG_LAYER_NOT_OBJECT", "submission", "", "type")));
	}

	@Test
	void appliesBundledImmutableReferencesAndFormatAssertions() {
		var artifact = """
				{"contractVersion":1,"skywrightSchema":%s,"projectSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"contact":{"$ref":"urn:example:configuration:contact:v1"}},"required":["contact"]},"defaults":{},"defaultsCompletionWitness":{"contact":"maintainer@example.com"},"references":{"urn:example:configuration:contact:v1":{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"urn:example:configuration:contact:v1","type":"string","format":"email"}}}
				"""
			.formatted(this.contracts.skywrightSchemaIdentityJson());
		var contract = this.contracts.compile(artifact);

		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> contract.resolve("{\"contact\":\"not-an-email\"}"))
			.satisfies(error -> assertThat(error.errors()).containsExactly(
					new ConfigurationError("CONFIG_SCHEMA_VALIDATION", "submission", "/contact", "format")));
	}

	@Test
	void rejectsUnsupportedSchemaFeatures() {
		var unsupportedDialect = projectContract("""
				{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","properties":{}}
				""", "{}", "{}");
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> this.contracts.compile(unsupportedDialect))
			.satisfies(error -> assertThat(error.errors()).containsExactly(new ConfigurationError(
					"CONFIG_UNSUPPORTED_DIALECT", "project-contract", "/projectSchema/$schema", "$schema")));

		var mutableReference = projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"remote":{"$ref":"https://example.com/live.json"}}}
						""",
				"{}", "{}");
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> this.contracts.compile(mutableReference))
			.satisfies(error -> assertThat(error.errors())
				.containsExactly(new ConfigurationError("CONFIG_MUTABLE_EXTERNAL_REFERENCE", "project-contract",
						"/projectSchema/properties/remote/$ref", "$ref")));
	}

	@Test
	void rejectsInvalidSchemasAndApplicatorOwnershipBypasses() {
		var invalidSchema = projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{},"required":"not-an-array"}
						""",
				"{}", "{}");
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> this.contracts.compile(invalidSchema))
			.satisfies(error -> assertThat(error.errors()).containsExactly(
					new ConfigurationError("CONFIG_INVALID_PROJECT_SCHEMA", "project-schema", "/required", "type")));

		var applicatorBypass = projectContract(
				"""
						{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{},"allOf":[{"properties":{"reproducibility":{"properties":{"seed":{"maximum":10}}}}}]}
						""",
				"{}", "{}");
		assertThatExceptionOfType(ConfigurationContractException.class)
			.isThrownBy(() -> this.contracts.compile(applicatorBypass))
			.satisfies(error -> assertThat(error.errors()).containsExactly(new ConfigurationError(
					"CONFIG_OWNERSHIP_COLLISION", "project-schema", "/reproducibility/seed", "properties")));
	}

	@Test
	void packagedCorpusIsByteIdenticalToTheCanonicalSdkResource() throws IOException {
		byte[] canonical = Files.readAllBytes(Path.of("../sdk/src/skywright/_configuration_resources/corpus.json"));
		byte[] packaged = ConfigurationContractTest.class
			.getResourceAsStream("/META-INF/skywright/configuration/corpus.json")
			.readAllBytes();

		assertThat(packaged).isEqualTo(canonical);
	}

	@Test
	void passesTheVersionedSharedConformanceCorpus() throws IOException {
		JsonNode corpus = JSON.readTree(
				ConfigurationContractTest.class.getResourceAsStream("/META-INF/skywright/configuration/corpus.json"));
		for (JsonNode fixture : corpus.path("cases")) {
			String name = fixture.path("name").asText();
			String artifact = """
					{"contractVersion":1,"skywrightSchema":%s,"projectSchema":%s,"defaults":%s,"defaultsCompletionWitness":%s,"references":%s}
					"""
				.formatted(this.contracts.skywrightSchemaIdentityJson(), fixture.get("projectSchema"),
						fixture.get("projectDefaults"), fixture.get("witness"),
						fixture.has("references") ? fixture.get("references") : "{}");
			if (fixture.has("contractErrors")) {
				assertThatExceptionOfType(ConfigurationContractException.class).as(name)
					.isThrownBy(() -> this.contracts.compile(artifact))
					.satisfies(error -> assertThat(error.errors())
						.containsExactlyElementsOf(errors(fixture, "contractErrors")));
				continue;
			}

			ConfigurationContract contract = this.contracts.compile(artifact);
			String submission = fixture.has("rawSubmission") ? fixture.path("rawSubmission").asText()
					: fixture.path("submission").toString();
			if (fixture.has("expectedErrors")) {
				assertThatExceptionOfType(ConfigurationContractException.class).as(name)
					.isThrownBy(() -> contract.resolve(submission))
					.satisfies(error -> assertThat(error.errors())
						.containsExactlyElementsOf(errors(fixture, "expectedErrors")));
			}
			else {
				assertThat(contract.resolve(submission)).as(name).isEqualTo(fixture.get("expectedResolved"));
			}
		}
	}

	private List<ConfigurationError> errors(JsonNode fixture, String field) {
		List<ConfigurationError> errors = new ArrayList<>();
		fixture.path(field)
			.forEach(error -> errors.add(new ConfigurationError(error.path("code").asText(),
					error.path("source").asText(), error.path("pointer").asText(), error.path("keyword").asText())));
		return errors;
	}

	private String projectContract(String schema, String defaults, String witness) {
		return """
				{"contractVersion":1,"skywrightSchema":%s,"projectSchema":%s,"defaults":%s,"defaultsCompletionWitness":%s,"references":{}}
				"""
			.formatted(contracts.skywrightSchemaIdentityJson(), schema, defaults, witness);
	}

}
