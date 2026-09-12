package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials;
import de.zorro909.skywright.backend.rundefinition.RunDefinition;
import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import de.zorro909.skywright.backend.rundefinition.EligibleTarget;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;

/** The two prototype targets share resolution, acceptance and dispatch. */
sealed interface ManagedRunTarget permits LocalAmdRunTarget, VastRunTarget {

	String identity();

	String acceleratorBackend();

	TargetClass targetClass(int gpuCount);

	EligibleTarget eligible(int gpuCount);

	void requireReady(int gpuCount);

	List<ManagedRunForms.Check> checks();

	String pullNamespace(boolean privateImage);

	OrchestratorTaskSpecification project(RunDefinition definition, RuntimeMaterials materials, boolean privateImage);

}
