package de.zorro909.skywright.backend.orchestration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

record SkyPilotBridgeSettings(int controlQueueCapacity, int heldQueueCapacity, Duration shutdownGrace) {

	static final String SKY_PILOT_VERSION = loadSkyPilotVersion();

	SkyPilotBridgeSettings {
		if (controlQueueCapacity < 1 || heldQueueCapacity < 1) {
			throw new IllegalArgumentException("bridge queue capacities must be positive");
		}
		if (shutdownGrace.isNegative()) {
			throw new IllegalArgumentException("shutdown grace must not be negative");
		}
	}

	private static String loadSkyPilotVersion() {
		try (var input = SkyPilotBridgeSettings.class.getResourceAsStream("/META-INF/skywright/skypilot-version.txt")) {
			if (input == null) {
				throw new IllegalStateException("SkyPilot version resource is missing");
			}
			var version = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
			if (version.isEmpty()) {
				throw new IllegalStateException("SkyPilot version resource is empty");
			}
			return version;
		}
		catch (IOException failure) {
			throw new IllegalStateException("SkyPilot version resource cannot be read", failure);
		}
	}

}
