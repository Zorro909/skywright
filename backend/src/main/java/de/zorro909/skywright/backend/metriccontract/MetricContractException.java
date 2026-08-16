package de.zorro909.skywright.backend.metriccontract;

import java.util.Collection;
import java.util.List;

/** Deterministic failures that make a Training Project Version not runnable. */
public final class MetricContractException extends IllegalArgumentException {

	private final List<MetricError> errors;

	MetricContractException(Collection<MetricError> errors) {
		super(errors.stream()
			.map(MetricError::code)
			.sorted()
			.reduce((left, right) -> left + "; " + right)
			.orElse("metric contract failure"));
		this.errors = errors.stream().sorted().toList();
	}

	public List<MetricError> errors() {
		return this.errors;
	}

}
