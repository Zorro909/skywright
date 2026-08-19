package de.zorro909.skywright.backend.runstore;

import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Injected resolved Target Storage descriptor with no registration or secret persistence
 * behavior.
 */
public record ResolvedTargetStorage(String storageId, URI endpoint, String bucket, Region region,
		boolean pathStyleAccess, Map<String, String> compatibilityOptions, AwsCredentialsProvider credentials,
		String trainingProjectId, String runId) {

	public ResolvedTargetStorage {
		compatibilityOptions = Map.copyOf(compatibilityOptions);
	}
}
