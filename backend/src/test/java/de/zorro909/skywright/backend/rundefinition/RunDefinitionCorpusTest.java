package de.zorro909.skywright.backend.rundefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
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
	}

}
