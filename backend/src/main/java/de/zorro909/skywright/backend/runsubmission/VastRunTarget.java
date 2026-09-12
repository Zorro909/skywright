package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.rundefinition.EligibleTarget;
import de.zorro909.skywright.backend.targetstorage.TargetClass;
import java.util.List;

/** Admission stays closed until the unchanged adapter has a proven rental guard. */
final class VastRunTarget implements ManagedRunTarget {

	static final String ON_DEMAND_ID = "vast/on-demand";

	static final String INTERRUPTIBLE_ID = "vast/spot";

	private final boolean spot;

	private final VastRunTargetSettings settings;

	VastRunTarget(VastRunTargetSettings settings, boolean spot) {
		this.spot = spot;
		this.settings = settings;
	}

	public String identity() {
		return spot ? INTERRUPTIBLE_ID : ON_DEMAND_ID;
	}

	public String acceleratorBackend() {
		return "cuda";
	}

	public TargetClass targetClass(int count) {
		return spot ? TargetClass.CLOUD_SPOT : TargetClass.CLOUD_ON_DEMAND;
	}

	public void requireReady(int count) {
		throw new RunSubmissionException(spot ? "VAST_BUDGET_UNVERIFIED" : "VAST_LAUNCH_PRICE_UNPROVEN", 503);
	}

	public EligibleTarget eligible(int count) {
		var target = settings.target(spot);
		return new EligibleTarget("vast", targetClass(count), acceleratorBackend(), target.gpuModel(), 1,
				target.gpuMemoryBytes());
	}

	public String pullNamespace(boolean privateImage) {
		return null;
	}

	public de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification project(
			de.zorro909.skywright.backend.rundefinition.RunDefinition definition,
			de.zorro909.skywright.backend.runtimeassembly.RuntimeMaterials materials, boolean privateImage) {
		return new de.zorro909.skywright.backend.runtimeassembly.VastRuntimeProjection().project(definition, materials,
				settings.target(spot));
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
				new ManagedRunForms.Check("launchPrice", bidBounded(),
						spot ? (bidBounded() ? "READY" : "VAST_BID_UNAVAILABLE") : "VAST_LAUNCH_PRICE_UNPROVEN",
						spot ? "An explicit bid bounds active rental compute only. Storage, traffic and replacements require separate budget evidence."
								: "The actual on-demand rental price is unproved; a catalog quote is insufficient."),
				new ManagedRunForms.Check("budget", false, "VAST_BUDGET_UNVERIFIED",
						"Verify current credit, disabled automatic top-up, disk and traffic allowances, and bounded runtime and cleanup before renting."),
				new ManagedRunForms.Check("qualification", false,
						spot ? "VAST_INTERRUPTIBLE_UNQUALIFIED" : "VAST_ON_DEMAND_UNQUALIFIED",
						"Private image pull, NVIDIA training, portable storage, logs, cancellation, usage and complete rental deletion still need live evidence."));
	}

	String purchaseMode() {
		return spot ? "spot" : "on-demand";
	}

	String displayName() {
		return spot ? "Vast.ai interruptible" : "Vast.ai on-demand";
	}

	private boolean bidBounded() {
		try {
			return spot && settings.target(true).maxBidHourlyCost() != null;
		}
		catch (RunSubmissionException unavailable) {
			return false;
		}
	}

}
