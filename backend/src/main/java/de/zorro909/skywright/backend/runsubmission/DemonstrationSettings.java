package de.zorro909.skywright.backend.runsubmission;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Installation-owned references into the existing project and Dataset catalogs. */
@ConfigurationProperties(prefix = "skywright.demonstration", ignoreUnknownFields = false)
public record DemonstrationSettings(UUID trainingProjectId, String manifestArtifactDigest, UUID datasetDefinitionId,
		String displayName, Map<String, Object> configuration) {
	public DemonstrationSettings {
		configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
	}

	boolean installed() {
		return trainingProjectId != null && manifestArtifactDigest != null && datasetDefinitionId != null
				&& displayName != null && !displayName.isBlank();
	}

	LocalRunRequest request(UUID submissionId, String target) {
		if (!installed())
			throw new RunSubmissionException("WORKLOAD_NOT_INSTALLED", 503);
		return new LocalRunRequest(submissionId, trainingProjectId, manifestArtifactDigest, datasetDefinitionId, null,
				null, target, 1, configuration, null);
	}
}
