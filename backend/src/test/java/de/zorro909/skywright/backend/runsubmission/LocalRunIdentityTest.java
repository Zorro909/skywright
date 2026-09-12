package de.zorro909.skywright.backend.runsubmission;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalRunIdentityTest {

	@Test
	void anUnseededRequestKeepsItsPre233DurableDigest() {
		var request = new LocalRunRequest(UUID.fromString("00000000-0000-4000-8000-000000000233"),
				UUID.fromString("00000000-0000-4000-8000-000000000001"), "sha256:" + "1".repeat(64),
				UUID.fromString("00000000-0000-4000-8000-000000000002"), null, null, "local/amd", 1, Map.of(), null);
		assertThat(ManagedRuns.digest(request))
			.isEqualTo("3f60228c5022cad40f6ea42b6e9740712d5d4e78ddedfb761aa307cdf80ba84a");
		var seeded = new LocalRunRequest(request.submissionId(), request.trainingProjectId(),
				request.manifestArtifactDigest(), request.datasetDefinitionId(), null, null, request.target(), 1,
				Map.of(), null,
				new LocalRunRequest.CheckpointSeed(UUID.fromString("00000000-0000-4000-8000-000000000003"),
						"skywright-checkpoint:v1:4:sha256:" + "2".repeat(64)));
		assertThat(ManagedRuns.digest(seeded)).isNotEqualTo(ManagedRuns.digest(request));
	}

}
