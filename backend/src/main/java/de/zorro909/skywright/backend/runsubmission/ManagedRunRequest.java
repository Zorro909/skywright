package de.zorro909.skywright.backend.runsubmission;

import java.util.UUID;

/**
 * The caller's stable intent; installation references are resolved only on first
 * acceptance.
 */
record ManagedRunRequest(UUID submissionId, String workload, String target) {
}
