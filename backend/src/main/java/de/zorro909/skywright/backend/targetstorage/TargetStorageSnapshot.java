package de.zorro909.skywright.backend.targetstorage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

record TargetStorageSnapshot(UUID id, String name, TargetStoragePurpose purpose, String bucket,
		long registrationRevision, boolean activated, Long activeRevision, Long candidateRevision,
		Map<Long, TargetStorageConfiguration> configurations, List<TargetStorageBinding> bindings,
		List<TargetStorageAssessment> assessments, CapabilityAvailability availability) {
	TargetStorageAggregate restore() {
		return TargetStorageAggregate.restore(this.id, this.name, this.purpose, this.bucket, this.registrationRevision,
				this.activated, this.activeRevision, this.candidateRevision, this.configurations, this.bindings,
				this.assessments, this.availability);
	}
}
