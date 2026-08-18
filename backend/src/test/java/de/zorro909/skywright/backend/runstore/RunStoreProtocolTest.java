package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RunStoreProtocolTest {

	@Test
	void buildsPortableV1ObjectIdentities() {
		RunStoreProtocol protocol = new RunStoreProtocol("project-α", "run-1");

		assertThat(protocol.runPrefix()).isEqualTo("project-%CE%B1/run-1/v1/");
		assertThat(protocol.checkpointKey(42, "a".repeat(64)))
			.isEqualTo("project-%CE%B1/run-1/v1/checkpoints/0000000000000000042/" + "a".repeat(64) + ".safetensors");
		assertThat(protocol.artifactKey("123e4567-e89b-12d3-a456-426614174000", 7, "plots/loss 100%.png"))
			.isEqualTo("project-%CE%B1/run-1/v1/artifacts/123e4567-e89b-12d3-a456-426614174000/"
					+ "0000000000000000007/plots%2Floss%20100%25.png");
	}

	@Test
	void checkpointReferenceRoundTripsWithoutAStorageLocation() {
		CheckpointReference reference = new CheckpointReference(42, "b".repeat(64));

		assertThat(reference.toString()).isEqualTo("skywright-checkpoint:v1:42:sha256:" + "b".repeat(64));
		assertThat(CheckpointReference.parse(reference.toString())).isEqualTo(reference);
	}

	@Test
	void rejectsInvalidIdentitiesBeforeKeyConstruction() {
		assertThatThrownBy(() -> new RunStoreProtocol("project", "")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Run identity");
		assertThatThrownBy(() -> new RunStoreProtocol("project", "run").checkpointKey(-1, "a".repeat(64)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Step");
		assertThatThrownBy(() -> new RunStoreProtocol("project", "run").attemptRecordKey("not-a-uuid"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Execution Attempt");
	}

}
