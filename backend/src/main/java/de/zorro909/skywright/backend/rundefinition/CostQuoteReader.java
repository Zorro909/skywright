package de.zorro909.skywright.backend.rundefinition;

import java.time.Instant;

/** Resolves one immutable pricing snapshot for all offerings eligible at quote time. */
@FunctionalInterface
public interface CostQuoteReader {

	CostQuoteAssessment resolve(TargetRequest request, String reportingCurrency, Instant quoteTime);

}
