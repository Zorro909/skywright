package de.zorro909.skywright.backend.orchestration;

import java.util.Objects;
import java.util.Optional;

public record OrchestratorResult<T>(T value, BridgeFailure failure) {

	public OrchestratorResult {
		if ((value == null) == (failure == null)) {
			throw new IllegalArgumentException("result must contain exactly one of value or failure");
		}
	}

	public static <T> OrchestratorResult<T> accepted(T value) {
		return new OrchestratorResult<>(Objects.requireNonNull(value, "value"), null);
	}

	public static <T> OrchestratorResult<T> failure(BridgeFailure failure) {
		return new OrchestratorResult<>(null, Objects.requireNonNull(failure, "failure"));
	}

	public Optional<T> acceptedValue() {
		return Optional.ofNullable(this.value);
	}

	public Optional<BridgeFailure> rejectedBy() {
		return Optional.ofNullable(this.failure);
	}

}
