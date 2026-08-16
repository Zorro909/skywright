package de.zorro909.skywright.backend.metriccontract;

import java.math.BigDecimal;

/** Semantic and presentation fields for one declared metric. */
public record MetricDefinition(String name, String numericKind, String unit, String recordingBasis, String comparison,
		String stepReduction, BigDecimal minimum, BigDecimal maximum, String displayName, String description) {
}
