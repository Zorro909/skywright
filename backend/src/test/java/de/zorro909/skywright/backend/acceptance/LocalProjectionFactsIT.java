package de.zorro909.skywright.backend.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import de.zorro909.skywright.backend.credential.CredentialBinding;
import de.zorro909.skywright.backend.credential.LocalProjectionFacts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("real-service")
class LocalProjectionFactsIT {

	@Test
	void pinsSurviveRestartRejectReplacementAndRetainImmutableReleaseEvidence() throws Exception {
		try (var backend = BackendFixture.start()) {
			UUID consumer = UUID.randomUUID();
			var binding = new CredentialBinding(UUID.randomUUID(), 2, "local/training", CredentialBinding.Kind.S3,
					"outputs", "training-process", "project-writer", "project-prefix", "read-write-delete",
					Instant.now().minusSeconds(60), null, true);
			backend.bean(LocalProjectionFacts.class).begin(consumer, "run-store", binding);
			backend.restart();
			var facts = backend.bean(LocalProjectionFacts.class);
			assertThat(facts.forConsumer(consumer)).singleElement().satisfies(fact -> {
				assertThat(fact.revision()).isEqualTo(2);
				assertThat(fact.releasedAt()).isNull();
			});
			assertThatThrownBy(() -> facts.begin(consumer, "run-store", binding)).isInstanceOf(RuntimeException.class);
			facts.release(consumer);
			var released = facts.forConsumer(consumer);
			facts.release(consumer);
			assertThat(facts.forConsumer(consumer)).isEqualTo(released);
			assertThat(released).allSatisfy(fact -> assertThat(fact.releasedAt()).isNotNull());
			assertThatThrownBy(() -> facts.begin(consumer, "dataset", binding))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(JsonMapper.builder().build().writeValueAsString(released)).doesNotContain("token", "secret",
					"local/training");
		}
	}

}
