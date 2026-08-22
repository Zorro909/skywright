package de.zorro909.skywright.backend.orchestration;

import java.util.Objects;

public record ControlRequest(String jobName, Action action) {

	public ControlRequest {
		if (jobName == null || jobName.isBlank()) {
			throw new IllegalArgumentException("job name must not be blank");
		}
		Objects.requireNonNull(action, "action");
	}

	public enum Action {

		CANCEL

	}

}
