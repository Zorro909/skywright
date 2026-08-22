package de.zorro909.skywright.backend.rundefinition;

import java.util.List;

/** Current supported-target evidence or stable dependency failures. */
public record TargetEligibilityAssessment(List<EligibleTarget> targets, List<RunDefinitionFailure> failures) {

	public TargetEligibilityAssessment {
		targets = List.copyOf(targets);
		failures = List.copyOf(failures);
	}

}
