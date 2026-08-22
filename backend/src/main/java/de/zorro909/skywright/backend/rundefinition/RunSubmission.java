package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import de.zorro909.skywright.backend.projectversion.TrainingProjectBinding;

/** Domain submission accepted by the write-free Run Definition resolver. */
public record RunSubmission(TrainingProjectBinding trainingProject, String manifestArtifactDigest,
		String configurationJson, DatasetDefinitionReference datasetDefinition, TargetRequest targetRequest,
		UUID executionStorageOverride, Boolean repatriationEnabledOverride, UUID repatriationStorageOverride,
		Integer maximumRecoveryDebt, Duration runtimeCeiling, BigDecimal costCeiling, boolean orderingReset) {
}
