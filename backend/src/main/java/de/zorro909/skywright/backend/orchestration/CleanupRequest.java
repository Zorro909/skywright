package de.zorro909.skywright.backend.orchestration;

public record CleanupRequest(String clusterName) {

	public CleanupRequest {
		if (clusterName == null || clusterName.isBlank()) {
			throw new IllegalArgumentException("cluster name must not be blank");
		}
	}

}
