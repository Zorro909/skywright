package de.zorro909.skywright.backend.targetstorage;

import java.util.Objects;
import java.util.UUID;

record TargetStorageBindingRevision(TargetStorageRole role, UUID bindingId, long bindingRevision) {

	TargetStorageBindingRevision {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(bindingId, "bindingId");
		if (bindingRevision < 1) {
			throw new IllegalArgumentException("bindingRevision must be positive");
		}
	}

	static TargetStorageBindingRevision from(TargetStorageBinding binding) {
		return new TargetStorageBindingRevision(binding.role(), binding.bindingId(), binding.bindingRevision());
	}

}
