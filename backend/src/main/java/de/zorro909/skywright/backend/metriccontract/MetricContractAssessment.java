package de.zorro909.skywright.backend.metriccontract;

import java.util.List;

/** Backend decision about whether one Training Project Version can run. */
public record MetricContractAssessment(boolean runnable, MetricCatalog catalog, List<MetricError> errors) {

	public MetricContractAssessment {
		errors = List.copyOf(errors);
	}

}
