package de.zorro909.skywright.backend.rundefinition;

/** Narrow read port for exact Dataset Definition facts. */
@FunctionalInterface
public interface DatasetDefinitionReader {

	DatasetDefinitionAssessment assess(DatasetDefinitionReference reference);

}
