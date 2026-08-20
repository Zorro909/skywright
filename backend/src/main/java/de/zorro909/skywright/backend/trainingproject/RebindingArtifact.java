package de.zorro909.skywright.backend.trainingproject;

record RebindingArtifact(ReferencedProjectArtifact.Kind kind, String repository, String digest, boolean verified,
		String failureCode) {
}
