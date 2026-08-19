package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
final class TargetStorageQualification {

	private final TargetStorageRegistry registry;

	private final TargetStorageQualificationProbe probe;

	TargetStorageQualification(TargetStorageRegistry registry, TargetStorageQualificationProbe probe) {
		this.registry = registry;
		this.probe = probe;
	}

	TargetStorageView qualify(UUID storageId) {
		TargetStorageQualificationRequest request = this.registry.qualificationRequest(storageId);
		TargetStorageAssessment assessment = this.probe.qualify(request);
		this.registry.recordQualification(storageId, assessment);
		return this.registry.get(storageId);
	}

	void qualifyWhenReady(UUID storageId) {
		TargetStorageQualificationRequest request = this.registry.qualificationRequest(storageId);
		var readyRoles = request.bindings()
			.stream()
			.filter(binding -> binding.readiness() == BindingReadiness.READY)
			.map(TargetStorageBinding::role)
			.collect(Collectors.toSet());
		if (readyRoles.containsAll(request.purpose().requiredRoles())) {
			this.qualify(storageId);
		}
	}

}
