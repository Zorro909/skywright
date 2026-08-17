package de.zorro909.skywright.backend.projectversion;

import java.time.Instant;
import java.util.List;

/** Age-bearing result of one live registry enumeration for display. */
public record ProjectVersionDiscovery(List<ProjectVersionReference> versions, Instant observedAt,
		List<ProjectVersionFailure> errors) {

	public ProjectVersionDiscovery {
		versions = List.copyOf(versions);
		errors = List.copyOf(errors);
	}

	public boolean registryAvailable() {
		return this.errors.isEmpty();
	}

}
