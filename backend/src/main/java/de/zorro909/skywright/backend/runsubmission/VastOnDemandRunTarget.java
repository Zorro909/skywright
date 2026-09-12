package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.rundefinition.EligibleTarget;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;
import org.springframework.stereotype.Component;

/** Admission stays closed until the unchanged adapter has a proven rental guard. */
@Component
final class VastOnDemandRunTarget implements ManagedRunTarget {

	static final String ID = "vast/on-demand";

	private final VastRunTargetSettings settings;

	VastOnDemandRunTarget(VastRunTargetSettings settings) {
		this.settings = settings;
	}

	public String identity() {
		return ID;
	}

	public String acceleratorBackend() {
		return "cuda";
	}

	public TargetClass targetClass(int count) {
		return TargetClass.CLOUD_ON_DEMAND;
	}

	public void requireReady(int count) {
		throw new RunSubmissionException("VAST_LAUNCH_PRICE_UNPROVEN", 503);
	}

	public EligibleTarget eligible(int count) {
		var target = settings.target();
		return new EligibleTarget("vast", TargetClass.CLOUD_ON_DEMAND, acceleratorBackend(), target.gpuModel(), 1,
				target.gpuMemoryBytes());
	}

	public String pullNamespace(boolean privateImage) {
		return null;
	}

	public de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification project(
			de.zorro909.skywright.backend.rundefinition.RunDefinition definition,
			de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials materials, boolean privateImage) {
		return new de.zorro909.skywright.backend.runtimeassembly.VastRuntimeProjection().project(definition, materials,
				settings.target());
	}

	public List<ManagedRunForms.Check> checks() {
		return List.of(
				new ManagedRunForms.Check("costQuote", false, "VAST_COST_QUOTE_UNAVAILABLE",
						"No current Cost Quote is joined to this workload and an enforceable rental limit."),
				new ManagedRunForms.Check("credentials", false, "VAST_PROVIDER_PROJECTION_UNAVAILABLE",
						"A Vault enrollment alone does not establish a validated SkyPilot provider binding and projection."),
				new ManagedRunForms.Check("storage", false, "VAST_STORAGE_UNQUALIFIED",
						"Cloud Dataset and Run Store access still need qualification from the selected rental."),
				new ManagedRunForms.Check("registry", false, "VAST_REGISTRY_PULL_UNQUALIFIED",
						"The pinned private CUDA image still needs a target-side pull with the recorded registry binding."),
				new ManagedRunForms.Check("launchPrice", false, "VAST_LAUNCH_PRICE_UNPROVEN",
						"The actual rental is not proven below USD 0.15/hour. SkyPilot searches again at launch; a catalog quote is insufficient."),
				new ManagedRunForms.Check("budget", false, "VAST_BUDGET_UNVERIFIED",
						"Verify current credit, disabled automatic top-up, disk and traffic allowances, and bounded runtime and cleanup before renting."),
				new ManagedRunForms.Check("qualification", false, "VAST_ON_DEMAND_UNQUALIFIED",
						"Private image pull, NVIDIA training, portable storage, logs, cancellation, usage and complete rental deletion still need live evidence."));
	}

}
