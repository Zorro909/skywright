package de.zorro909.skywright.backend.rundefinition;

/** Exact logical Dataset Definition selected by a Run Submission. */
public record DatasetDefinitionReference(String datasetIdentity, String version, String contentFingerprint) {
}
