package de.zorro909.skywright.backend.targetstorage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class BindingUnavailableQualificationProbe implements TargetStorageQualificationProbe {

	BindingUnavailableQualificationProbe() {
	}

	@Override
	public TargetStorageAssessment qualify(TargetStorageQualificationRequest request) {
		Instant observed = Instant.now();
		List<TargetStorageCapabilityResult> results = TargetStorageCapabilities.REQUIRED.stream()
			.map(capability -> TargetStorageCapabilityResult.failure(capability, "credential-binding-unavailable",
					"A ready backend Credential Binding is required to exercise this capability", Map.of()))
			.toList();
		return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), observed, Instant.now(),
				CapabilityAvailability.TRANSIENTLY_UNAVAILABLE, request.bindings(), results);
	}

}
