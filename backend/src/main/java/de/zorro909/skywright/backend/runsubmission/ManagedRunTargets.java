package de.zorro909.skywright.backend.runsubmission;

import org.springframework.stereotype.Component;

/** Finite prototype selection. A pinned target never selects another adapter. */
@Component
final class ManagedRunTargets {

	private final LocalAmdRunTarget local;

	private final VastOnDemandRunTarget vast;

	ManagedRunTargets(LocalAmdRunTarget local, VastOnDemandRunTarget vast) {
		this.local = local;
		this.vast = vast;
	}

	ManagedRunTarget select(String identity) {
		if (VastOnDemandRunTarget.ID.equals(identity))
			return vast;
		if (identity != null && identity.equals(local.identity()))
			return local;
		throw new RunSubmissionException("TARGET_INELIGIBLE", 422);
	}

	LocalAmdRunTarget local() {
		return local;
	}

}
