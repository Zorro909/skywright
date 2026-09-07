package de.zorro909.skywright.backend.runlog;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;

/** Exercises the publication fence through the real Spring transaction proxy. */
public final class RunLogCaptureProbe {

	private RunLogCaptureProbe() {
	}

	public static void replacedProducerCannotPublish(RunLogCaptureStore store, UUID run, Runnable expireLease) {
		var first = store.claim(run);
		assertThat(first).isNotNull();
		assertThat(store.claim(run)).isNull();
		expireLease.run();
		var replacement = store.claim(run);
		assertThat(replacement).isNotNull();
		assertThat(replacement.token()).isNotEqualTo(first.token());
		store.publish(first, cursor -> {
			throw new AssertionError("Replaced producer published");
		});
		store.publish(replacement, cursor -> new RunLogCaptureStore.Saved(cursor, null, null));
		assertThat(store.finalization(run)).isNull();
	}

}
