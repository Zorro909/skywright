package de.zorro909.skywright.backend.trainingproject;

import java.util.UUID;

/**
 * Revision-pinned active binding used by Run Submission and Run Definition resolution.
 */
public record ResolvedTrainingProjectBinding(UUID projectId, long bindingRevision, String repository) {
}
