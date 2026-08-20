package de.zorro909.skywright.backend.trainingproject;

public record ReferencedProjectArtifact(Kind kind, String digest) {

	public enum Kind {

		VERSION_MANIFEST, IMAGE, CONFIGURATION_CONTRACT, METRIC_CONTRACT

	}

}
