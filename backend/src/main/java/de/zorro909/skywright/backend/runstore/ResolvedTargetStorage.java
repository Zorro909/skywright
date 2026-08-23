package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Injected resolved Target Storage descriptor with no registration or secret persistence
 * behavior.
 */
public record ResolvedTargetStorage(String storageId, URI endpoint, String bucket, Region region,
		boolean pathStyleAccess, Map<String, String> compatibilityOptions, AwsCredentialsProvider credentials,
		String trainingProjectId, String runId, UUID credentialBindingId, long credentialBindingRevision) {

	public ResolvedTargetStorage {
		compatibilityOptions = Map.copyOf(compatibilityOptions);
	}
}
