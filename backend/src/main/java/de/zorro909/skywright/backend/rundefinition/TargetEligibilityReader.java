package de.zorro909.skywright.backend.rundefinition;

/** Narrow read port for supported target capability evidence. */
@FunctionalInterface
public interface TargetEligibilityReader {

	TargetEligibilityAssessment assess();

}
