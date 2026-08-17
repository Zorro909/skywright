package de.zorro909.skywright.backend.projectversion;

import java.util.Map;

import de.zorro909.skywright.backend.configurationcontract.ConfigurationContract;
import de.zorro909.skywright.backend.metriccontract.MetricCatalog;

/** Independently verified immutable Training Project Version. */
public record TrainingProjectVersion(String projectIdentity, String versionLabel, String manifestArtifactDigest,
		String sourceRevision, String pipeline, Map<String, String> images, Map<String, String> environmentProfiles,
		String configurationContractDigest, String metricContractDigest, ConfigurationContract configurationContract,
		MetricCatalog metricCatalog) {

	public TrainingProjectVersion {
		images = Map.copyOf(images);
		environmentProfiles = Map.copyOf(environmentProfiles);
	}

	public String imageFor(String acceleratorBackend) {
		String image = this.images.get(acceleratorBackend);
		if (image == null) {
			throw new ProjectVersionException(
					new ProjectVersionFailure("PROJECT_CAPABILITIES_INCOMPATIBLE", "/acceleratorBackend"));
		}
		return image;
	}

}
