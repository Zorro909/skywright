package de.zorro909.skywright.backend.rundefinition;

/** Caller-supplied source definition and existing resume-compatibility decision. */
public record CheckpointSeedFacts(RunDefinition sourceDefinition, boolean libraryConfigurationCompatible) {
}
