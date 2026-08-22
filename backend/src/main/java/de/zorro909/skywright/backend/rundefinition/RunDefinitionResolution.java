package de.zorro909.skywright.backend.rundefinition;

import java.util.List;

/** Either one complete immutable definition or a non-empty ordered failure list. */
public record RunDefinitionResolution(RunDefinition definition, List<RunDefinitionFailure> failures) {

	public RunDefinitionResolution {
		failures = List.copyOf(failures);
		if ((definition == null) == failures.isEmpty()) {
			throw new IllegalArgumentException("resolution must contain exactly one definition or failures");
		}
	}

	public boolean accepted() {
		return this.definition != null;
	}

}
