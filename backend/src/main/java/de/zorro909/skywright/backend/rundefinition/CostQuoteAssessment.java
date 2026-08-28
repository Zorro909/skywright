package de.zorro909.skywright.backend.rundefinition;

import java.util.List;

/** Either complete candidate evidence or stable failures for an incomplete quote. */
public record CostQuoteAssessment(List<CostQuoteCandidate> candidates, List<RunDefinitionFailure> failures) {

	public CostQuoteAssessment {
		candidates = List.copyOf(candidates);
		failures = List.copyOf(failures);
	}

}
