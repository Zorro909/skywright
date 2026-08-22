package de.zorro909.skywright.backend.orchestration;

import org.graalvm.polyglot.PolyglotException;

final class SkyPilotClientFailure extends Exception {

	private final BridgeFailure.FailureCause causeCategory;

	SkyPilotClientFailure(BridgeFailure.FailureCause causeCategory, String message) {
		super(message);
		this.causeCategory = causeCategory;
	}

	BridgeFailure.FailureCause causeCategory() {
		return this.causeCategory;
	}

	static SkyPilotClientFailure from(PolyglotException failure) {
		var message = failure.getMessage() == null ? "SkyPilot guest call failed" : failure.getMessage();
		var normalized = message.toLowerCase(java.util.Locale.ROOT);
		var category = normalized.contains("auth") || normalized.contains("unauthorized")
				|| normalized.contains("forbidden") ? BridgeFailure.FailureCause.AUTHENTICATION
						: normalized.contains("connection") || normalized.contains("unreachable")
								? BridgeFailure.FailureCause.REACHABILITY : BridgeFailure.FailureCause.ADAPTER_CONTRACT;
		return new SkyPilotClientFailure(category, message.lines().findFirst().orElse("SkyPilot guest call failed"));
	}

}
