package de.zorro909.skywright.backend.rundefinition;

import java.util.List;

/** Read-only Dataset Definition admission result supplied by the catalog boundary. */
public record DatasetDefinitionAssessment(boolean available, List<RunDefinitionFailure> failures) {

	public DatasetDefinitionAssessment {
		failures = List.copyOf(failures);
	}

	public static DatasetDefinitionAssessment accepted() {
		return new DatasetDefinitionAssessment(true, List.of());
	}

}
