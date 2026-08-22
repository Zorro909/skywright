package de.zorro909.skywright.backend.orchestration;

import java.time.Instant;

public record SkyPilotAvailability(boolean available, BridgeFailure failure, Instant observedAt) {

	public static SkyPilotAvailability healthy() {
		return new SkyPilotAvailability(true, null, Instant.now());
	}

	public static SkyPilotAvailability unavailable(BridgeFailure failure) {
		return new SkyPilotAvailability(false, failure, Instant.now());
	}

}
