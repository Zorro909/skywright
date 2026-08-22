package de.zorro909.skywright.backend.rundefinition;

import java.util.Collection;
import java.util.List;

/** Stable failures emitted while decoding a Run Definition document. */
public final class RunDefinitionValidationException extends IllegalArgumentException {

	private final List<RunDefinitionFailure> failures;

	RunDefinitionValidationException(Collection<RunDefinitionFailure> failures) {
		super(failures.stream().map(RunDefinitionFailure::code).sorted().findFirst().orElse("RUN_DEFINITION_INVALID"));
		this.failures = failures.stream().distinct().sorted().toList();
	}

	public List<RunDefinitionFailure> failures() {
		return this.failures;
	}

}
