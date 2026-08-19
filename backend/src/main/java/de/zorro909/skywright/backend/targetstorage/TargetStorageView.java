package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.UUID;

record TargetStorageView(UUID id, String name, TargetStoragePurpose purpose, String bucket, long registrationRevision,
		boolean activated, boolean eligible, Long activeRevision, Long candidateRevision,
		TargetStorageConfiguration configuration, List<TargetStorageRevisionView> revisions,
		List<TargetStorageBinding> bindings, List<TargetStorageAssessment> assessments) {
}
