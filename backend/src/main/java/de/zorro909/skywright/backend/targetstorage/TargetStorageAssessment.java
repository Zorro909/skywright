package de.zorro909.skywright.backend.targetstorage;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record TargetStorageAssessment(UUID id, long configurationRevision, Instant observedFrom, Instant observedUntil,
		CapabilityAvailability availability, List<TargetStorageBinding> bindingRevisions,
		List<TargetStorageCapabilityResult> capabilities) {
	TargetStorageAssessment {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(observedFrom, "observedFrom");
		Objects.requireNonNull(observedUntil, "observedUntil");
		Objects.requireNonNull(availability, "availability");
		bindingRevisions = List.copyOf(bindingRevisions);
		capabilities = List.copyOf(capabilities);
		if (configurationRevision < 1L || observedUntil.isBefore(observedFrom) || bindingRevisions.isEmpty()
				|| capabilities.isEmpty()) {
			throw new IllegalArgumentException("invalid Target Storage assessment");
		}
		var roles = bindingRevisions.stream()
			.map(TargetStorageBinding::role)
			.collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(TargetStorageRole.class)));
		if (roles.size() != bindingRevisions.size() || !roles.equals(EnumSet.allOf(TargetStorageRole.class))) {
			throw new IllegalArgumentException("assessment requires exactly one revision for every binding role");
		}
		if (availability == CapabilityAvailability.AVAILABLE
				&& capabilities.stream().anyMatch(result -> !result.succeeded())) {
			throw new IllegalArgumentException("available assessment cannot contain failed capabilities");
		}
	}
}
