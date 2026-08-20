package de.zorro909.skywright.backend.targetstorage;

import java.util.Objects;
import java.util.UUID;

record TargetStorageBinding(TargetStorageRole role, UUID bindingId, long bindingRevision, BindingReadiness readiness) {
	TargetStorageBinding {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(bindingId, "bindingId");
		Objects.requireNonNull(readiness, "readiness");
		if (bindingRevision < 1L) {
			throw new IllegalArgumentException("bindingRevision must be positive");
		}
	}
}
