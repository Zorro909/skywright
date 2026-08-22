package de.zorro909.skywright.backend.rundefinition;

import java.math.BigDecimal;
import java.time.Duration;

import de.zorro909.skywright.backend.projectversion.TrainingProjectBinding;
import de.zorro909.skywright.backend.targetstorage.RunDefinitionStorageOverrides;

/** Domain submission accepted by the write-free Run Definition resolver. */
public record RunSubmission(TrainingProjectBinding trainingProject, String manifestArtifactDigest,
		String configurationJson, DatasetDefinitionReference datasetDefinition, TargetRequest targetRequest,
		RunDefinitionStorageOverrides storageOverrides, Integer maximumRecoveryDebt, Duration runtimeCeiling,
		BigDecimal costCeiling, boolean orderingReset) {
}
