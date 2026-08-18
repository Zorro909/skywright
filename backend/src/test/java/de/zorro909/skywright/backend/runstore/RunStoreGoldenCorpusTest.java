package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RunStoreGoldenCorpusTest {

	@Test
	void javaAcceptsTheSharedRunStoreGoldenCorpus() throws Exception {
		JsonNode corpus = JsonMapper.builder()
			.build()
			.readTree(Files.readString(Path.of("../protocol/run-store/v1/golden.json")));
		for (JsonNode item : corpus.path("identities")) {
			RunStoreProtocol protocol = new RunStoreProtocol(item.path("project").asText(), item.path("run").asText());
			long step = item.path("step").asLong();
			String digest = item.path("digest").asText();
			assertThat(protocol.runPrefix()).isEqualTo(item.path("runPrefix").asText());
			assertThat(protocol.checkpointKey(step, digest)).isEqualTo(item.path("checkpointKey").asText());
			assertThat(protocol.artifactKey(item.path("attempt").asText(), step, item.path("outputName").asText()))
				.isEqualTo(item.path("artifactKey").asText());
			assertThat(new CheckpointReference(step, digest).toString())
				.isEqualTo(item.path("checkpointReference").asText());
		}
		for (JsonNode reference : corpus.path("invalidReferences")) {
			assertThatThrownBy(() -> CheckpointReference.parse(reference.asText()))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

}
