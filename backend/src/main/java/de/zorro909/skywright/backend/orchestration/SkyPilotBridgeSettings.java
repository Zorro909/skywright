package de.zorro909.skywright.backend.orchestration;

import java.time.Duration;

record SkyPilotBridgeSettings(int controlQueueCapacity, int heldQueueCapacity, Duration shutdownGrace) {

	static final String SKY_PILOT_VERSION = "0.13.0";

	SkyPilotBridgeSettings {
		if (controlQueueCapacity < 1 || heldQueueCapacity < 1) {
			throw new IllegalArgumentException("bridge queue capacities must be positive");
		}
		if (shutdownGrace.isNegative()) {
			throw new IllegalArgumentException("shutdown grace must not be negative");
		}
	}

}
