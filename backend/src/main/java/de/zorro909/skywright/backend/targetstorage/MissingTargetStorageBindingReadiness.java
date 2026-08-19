package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

final class MissingTargetStorageBindingReadiness implements TargetStorageBindingReadiness {

	MissingTargetStorageBindingReadiness() {
	}

	@Override
	public BindingReadiness readiness(UUID bindingId, long bindingRevision, String consumingRole) {
		return BindingReadiness.MISSING;
	}

}
