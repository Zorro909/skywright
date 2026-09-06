package de.zorro909.skywright.backend.runstore;

/**
 * Process-authored identity read from a verified Execution Attempt Record, never a
 * SkyPilot fact.
 */
public record ExecutionAttemptReference(String runId, String attemptId, String projectVersion) {
}
