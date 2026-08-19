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
		boolean pathStyleAccess, AwsCredentialsProvider credentials, String trainingProjectId, String runId,
		Map<String, String> compatibilityOptions) {

	public ResolvedTargetStorage {
		compatibilityOptions = Map.copyOf(compatibilityOptions);
	}

	public ResolvedTargetStorage(String storageId, URI endpoint, String bucket, Region region, boolean pathStyleAccess,
			AwsCredentialsProvider credentials, String trainingProjectId, String runId) {
		this(storageId, endpoint, bucket, region, pathStyleAccess, credentials, trainingProjectId, runId, Map.of());
	}
}
