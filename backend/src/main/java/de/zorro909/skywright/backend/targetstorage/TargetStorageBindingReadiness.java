package de.zorro909.skywright.backend.targetstorage;

import java.util.UUID;

public interface TargetStorageBindingReadiness {

	BindingReadiness readiness(UUID bindingId, long bindingRevision, String consumingRole);

}
