package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
			assertThat(protocol.metricSegmentKey(item.path("attempt").asText(), step))
				.isEqualTo(item.path("metricSegmentKey").asText());
			assertThat(protocol.progressKey()).isEqualTo(item.path("progressKey").asText());
			assertThat(new CheckpointReference(step, digest).toString())
				.isEqualTo(item.path("checkpointReference").asText());
		}
		for (JsonNode reference : corpus.path("invalidReferences")) {
			assertThatThrownBy(() -> CheckpointReference.parse(reference.asText()))
				.isInstanceOf(IllegalArgumentException.class);
		}
		for (JsonNode item : corpus.path("progressRecords")) {
			ProgressRecord progress = ProgressRecord
				.decode(item.path("json").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			assertThat(progress.runId()).isEqualTo(item.path("runId").asText());
			assertThat(progress.currentStep()).isEqualTo(item.path("currentStep").asLong());
			assertThat(progress.latestDurableStep())
				.isEqualTo(item.path("latestDurableStep").isNull() ? null : item.path("latestDurableStep").asLong());
			assertThat(progress.latestDurableCheckpoint()).isEqualTo(item.path("latestDurableCheckpoint").isNull()
					? null : item.path("latestDurableCheckpoint").asText());
			assertThat(progress.targetStep())
				.isEqualTo(item.path("targetStep").isNull() ? null : item.path("targetStep").asLong());
			assertThat(progress.writtenAt().toString()).isEqualTo(item.path("writtenAt").asText());
		}
		for (JsonNode item : corpus.path("invalidProgressRecords")) {
			assertThatThrownBy(
					() -> ProgressRecord.decode(item.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.isInstanceOf(RunStoreIntegrityException.class)
				.hasMessageContaining("RUN_STORE_INCOMPATIBLE_SCHEMA");
		}
		for (JsonNode item : corpus.path("invalidProgressStepRecords")) {
			assertThatThrownBy(
					() -> ProgressRecord.decode(item.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.isInstanceOf(RunStoreIntegrityException.class)
				.hasMessageContaining("RUN_STORE_MALFORMED_PROGRESS");
		}
		byte[] progressBody = corpus.path("progressRecords")
			.get(0)
			.path("json")
			.asText()
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		for (JsonNode item : corpus.path("progressIntegrityMetadata")) {
			JsonNode metadata = item.path("metadata");
			RunStoreProtocol protocol = new RunStoreProtocol("project", "run-1");
			RunStoreObject object = new RunStoreObject(protocol.progressKey(), progressBody, "application/json",
					Map.of("skywright-sha256", sha256(progressBody), "skywright-size",
							Integer.toString(progressBody.length), "skywright-kind",
							metadata.path("skywright-kind").asText(), "skywright-schema",
							metadata.path("skywright-schema").asText()));
			RunStoreAccess access = new RunStoreAccess(protocol, new SingleObjectStore(object));
			if (item.path("valid").asBoolean()) {
				assertThat(access.readProgress().runId()).isEqualTo("run-1");
			}
			else {
				assertThatThrownBy(access::readProgress).isInstanceOf(RunStoreIntegrityException.class);
			}
		}
	}

	private static String sha256(byte[] body) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
	}

	private record SingleObjectStore(RunStoreObject object) implements RunStoreObjectStore {
		@Override
		public java.util.List<RunStoreObject> list(String prefix) {
			return java.util.List.of(object);
		}

		@Override
		public RunStoreObject get(String key) {
			return object.key().equals(key) ? object : null;
		}

		@Override
		public java.net.URI presignGet(String key, int expiresInSeconds, String contentType, String filename) {
			throw new UnsupportedOperationException();
		}
	}

}
