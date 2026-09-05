package de.zorro909.skywright.backend.trainingproject;

import java.util.UUID;

/** Non-secret view of Credential Binding status owned by the credential module. */
public interface TrainingProjectCredentialReadiness {

	RegistryReadiness readiness(UUID bindingId, String consumingRole, String repository);

}
