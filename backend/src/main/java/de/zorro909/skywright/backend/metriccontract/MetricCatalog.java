package de.zorro909.skywright.backend.metriccontract;

import java.util.List;
import java.util.Set;

/** Immutable runtime composition of the two pinned metric artifacts. */
public record MetricCatalog(String projectIdentity, String projectVersion, String projectContractDigest,
		MetricSchemaIdentity skywrightSchema, Set<String> units, List<MetricDefinition> projectDefinitions,
		List<MetricDefinition> systemDefinitions) {

	public MetricCatalog {
		units = Set.copyOf(units);
		projectDefinitions = List.copyOf(projectDefinitions);
		systemDefinitions = List.copyOf(systemDefinitions);
	}

}
