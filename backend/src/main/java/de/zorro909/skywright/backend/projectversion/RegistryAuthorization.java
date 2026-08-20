package de.zorro909.skywright.backend.projectversion;

import java.util.Optional;

/**
 * Supplies a transient authorization header without exposing it to project state or APIs.
 */
public interface RegistryAuthorization {

	Optional<String> authorization(String repository);

}
