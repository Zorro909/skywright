package de.zorro909.skywright.backend.orchestration;

import java.util.Objects;

public record OrchestratorOperation(String id, OperationKind kind) {

	public OrchestratorOperation {
		if (Objects.requireNonNull(id, "id").isBlank()) {
			throw new IllegalArgumentException("operation id must not be blank");
		}
		Objects.requireNonNull(kind, "kind");
	}

}
