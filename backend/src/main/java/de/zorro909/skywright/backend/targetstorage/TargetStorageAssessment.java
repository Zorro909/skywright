package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreS3CapabilityFloor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record TargetStorageAssessment(UUID id, long configurationRevision, Instant observedFrom, Instant observedUntil,
		CapabilityAvailability availability, List<TargetStorageBindingRevision> bindingRevisions,
		List<TargetStorageCapabilityResult> capabilities) {
	TargetStorageAssessment {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(observedFrom, "observedFrom");
		Objects.requireNonNull(observedUntil, "observedUntil");
		Objects.requireNonNull(availability, "availability");
		bindingRevisions = List.copyOf(bindingRevisions);
		capabilities = List.copyOf(capabilities);
		if (configurationRevision < 1L || observedUntil.isBefore(observedFrom) || capabilities.isEmpty()) {
			throw new IllegalArgumentException("invalid Target Storage assessment");
		}
		if (availability == CapabilityAvailability.AVAILABLE
				&& capabilities.stream().anyMatch(result -> !result.succeeded())) {
			throw new IllegalArgumentException("available assessment cannot contain failed capabilities");
		}
		List<String> observedCapabilities = capabilities.stream()
			.map(TargetStorageCapabilityResult::capability)
			.sorted()
			.toList();
		List<String> requiredCapabilities = RunStoreS3CapabilityFloor.requiredCapabilities().stream().sorted().toList();
		if (!observedCapabilities.equals(requiredCapabilities)) {
			throw new IllegalArgumentException("assessment must contain every required S3 capability exactly once");
		}
	}
}
