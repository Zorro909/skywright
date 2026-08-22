package de.zorro909.skywright.backend.rundefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RunDefinitionCorpusTest {

	@Test
	void javaAcceptsTheSharedRunDefinitionCorpus() throws Exception {
		JsonNode corpus = JsonMapper.builder()
			.build()
			.readTree(Files.readString(Path.of("../sdk/src/skywright/_run_definition_resources/corpus.json")));
		for (JsonNode value : corpus.path("valid")) {
			RunDefinition definition = RunDefinition.decode(value.toString());
			assertThat(JsonMapper.builder().build().readTree(definition.encode())).isEqualTo(value);
			definition.value().withObject("/configuration").put("changed", true);
			assertThat(definition.value().path("configuration").has("changed")).isFalse();
		}
		for (JsonNode invalid : corpus.path("invalid")) {
			assertThatThrownBy(() -> RunDefinition.decode(invalid.path("json").asText()))
				.isInstanceOf(RunDefinitionValidationException.class)
				.satisfies(error -> assertThat(((RunDefinitionValidationException) error).failures().getFirst().code())
					.isEqualTo(invalid.path("code").asText()));
		}
		for (JsonNode invalid : corpus.path("invalidMutations")) {
			JsonNode value = corpus.path("valid").get(0).deepCopy();
			set(value, invalid.path("pointer").asText(),
					JsonMapper.builder()
						.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
						.build()
						.readTree(invalid.path("replacementJson").asText()));
			assertThatThrownBy(() -> RunDefinition.decode(value.toString())).as(invalid.path("pointer").asText())
				.isInstanceOf(RunDefinitionValidationException.class)
				.satisfies(error -> assertThat(((RunDefinitionValidationException) error).failures().getFirst().code())
					.isEqualTo(invalid.path("code").asText()));
		}
		String template = corpus.path("valid").get(0).toString();
		for (JsonNode numberCase : corpus.path("numberLengthCases")) {
			String document = template.replace("9007199254740993", "1".repeat(numberCase.path("digits").asInt()));
			if (numberCase.path("code").isNull()) {
				assertThat(
						RunDefinition.decode(document).value().at("/configuration/nested/array/1").isIntegralNumber())
					.isTrue();
			}
			else {
				assertThatThrownBy(() -> RunDefinition.decode(document))
					.isInstanceOf(RunDefinitionValidationException.class)
					.satisfies(
							error -> assertThat(((RunDefinitionValidationException) error).failures().getFirst().code())
								.isEqualTo(numberCase.path("code").asText()));
			}
		}
	}

	private static void set(JsonNode root, String pointer, JsonNode replacement) {
		int separator = pointer.lastIndexOf('/');
		JsonNode parent = root.at(pointer.substring(0, separator));
		String token = pointer.substring(separator + 1);
		if (parent.isArray()) {
			((tools.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(token), replacement);
		}
		else {
			((tools.jackson.databind.node.ObjectNode) parent).set(token, replacement);
		}
	}

}
