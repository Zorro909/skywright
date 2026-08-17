package de.zorro909.skywright.backend.projectversion;

import java.util.List;

/** Backend decision about one exact pull-side version resolution. */
public record ProjectVersionAssessment(boolean runnable, TrainingProjectVersion version,
		List<ProjectVersionFailure> errors) {

	public ProjectVersionAssessment {
		errors = List.copyOf(errors);
	}

}
