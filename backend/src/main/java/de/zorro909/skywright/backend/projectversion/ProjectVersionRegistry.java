package de.zorro909.skywright.backend.projectversion;

import java.util.Optional;

/** Pull-side OCI boundary; implementations must not substitute cached authority. */
public interface ProjectVersionRegistry {

	Optional<String> pullArtifact(String repository, String reference);

	boolean imageAvailable(String repository, String digest);

}
