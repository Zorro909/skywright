package de.zorro909.skywright.backend.targetstorage;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An unavailable registered destination for acceptance tests that do not read S3. */
public final class RunStoreReferenceFixture {

	public static UUID register(TargetStorageRegistry registry, UUID runId) {
		return registry.register("fixture-" + runId, TargetStoragePurpose.RUN_OUTPUT, "fixture-" + runId,
				new TargetStorageConfiguration(URI.create("http://127.0.0.1:1"), "us-east-1", true, Map.of()),
				List.of());
	}

}
