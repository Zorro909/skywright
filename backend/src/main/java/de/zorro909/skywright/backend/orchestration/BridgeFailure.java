package de.zorro909.skywright.backend.orchestration;

import java.util.Objects;

public record BridgeFailure(String code, FailureCause cause, String diagnostic) {

	public static final String BUSY_CODE = "bridge-busy";

	public static final String UNAVAILABLE_CODE = "skypilot-unavailable";

	public BridgeFailure {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(cause, "cause");
		Objects.requireNonNull(diagnostic, "diagnostic");
	}

	public static BridgeFailure busy() {
		return new BridgeFailure(BUSY_CODE, FailureCause.SATURATION, "SkyPilot bridge queue is full");
	}

	public static BridgeFailure unavailable(FailureCause cause, String diagnostic) {
		return new BridgeFailure(UNAVAILABLE_CODE, cause, diagnostic);
	}

	public enum FailureCause {

		SATURATION, CLIENT_INITIALIZATION, AUTHENTICATION, REACHABILITY, VERSION_MISMATCH, ADAPTER_CONTRACT, SHUTDOWN

	}

}
