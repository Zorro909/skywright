package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runstore.ResolvedTargetStorage;
import de.zorro909.skywright.backend.runstore.RunStoreProtocol;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Non-secret fixed transfer instructions; the worker receives credentials only on stdin.
 */
public record LocalSeedWorkerJob(Storage source, Storage destination, UUID projectId, UUID predecessorRunId, UUID runId,
		String checkpointReference, String projectVersion) {
	public record Storage(String storageId, URI endpoint, String bucket, String region, boolean pathStyle,
			Map<String, String> options, UUID bindingId, long bindingRevision) {
		static Storage from(ResolvedTargetStorage value) {
			return new Storage(value.storageId(), value.endpoint(), value.bucket(), value.region().id(),
					value.pathStyleAccess(), value.compatibilityOptions(), value.credentialBindingId(),
					value.credentialBindingRevision());
		}
	}

	String destinationKey() {
		var reference = de.zorro909.skywright.backend.runstore.CheckpointReference.parse(checkpointReference);
		String stable = new RunStoreProtocol(projectId.toString(), runId.toString()).runPrefix();
		return stable.substring(0, stable.length() - 3) + "seed-v1/" + predecessorRunId + "/checkpoints/"
				+ "%019d".formatted(reference.step()) + "/" + reference.digest() + ".safetensors";
	}
}
