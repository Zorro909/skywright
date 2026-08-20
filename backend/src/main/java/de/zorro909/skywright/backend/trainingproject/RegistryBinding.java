package de.zorro909.skywright.backend.trainingproject;

import java.util.UUID;

record RegistryBinding(long revision, String repository, RegistryAccessMode accessMode,
		UUID resolverCredentialBindingId, UUID executionCredentialBindingId, RegistryReadiness readiness,
		String state) {
}
