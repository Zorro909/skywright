package de.zorro909.skywright.backend.runsubmission;

import org.springframework.stereotype.Component;

/** Finite prototype selection. A pinned target never selects another adapter. */
@Component
final class ManagedRunTargets {

	private final LocalAmdRunTarget local;

	private final VastRunTarget onDemand;

	private final VastRunTarget interruptible;

	ManagedRunTargets(LocalAmdRunTarget local, VastRunTargetSettings settings) {
		this.local = local;
		this.onDemand = new VastRunTarget(settings, false);
		this.interruptible = new VastRunTarget(settings, true);
	}

	ManagedRunTarget select(String identity) {
		if (VastRunTarget.ON_DEMAND_ID.equals(identity))
			return onDemand;
		if (VastRunTarget.INTERRUPTIBLE_ID.equals(identity))
			return interruptible;
		if (identity != null && identity.equals(local.identity()))
			return local;
		throw new RunSubmissionException("TARGET_INELIGIBLE", 422);
	}

	java.util.List<VastRunTarget> vastModes() {
		return java.util.List.of(onDemand, interruptible);
	}

	LocalAmdRunTarget local() {
		return local;
	}

}
