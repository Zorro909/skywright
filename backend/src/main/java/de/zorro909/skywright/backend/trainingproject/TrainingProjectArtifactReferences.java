package de.zorro909.skywright.backend.trainingproject;

import java.util.Set;
import java.util.UUID;

/** Run-owned query for project artifacts still referenced by undeleted Runs. */
public interface TrainingProjectArtifactReferences {

	Set<ReferencedProjectArtifact> referencedArtifacts(UUID projectId);

}
