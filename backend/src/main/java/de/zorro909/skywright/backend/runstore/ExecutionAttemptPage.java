package de.zorro909.skywright.backend.runstore;

import java.util.List;

public record ExecutionAttemptPage(List<ExecutionAttemptReference> attempts, String continuation) {
	public ExecutionAttemptPage {
		attempts = List.copyOf(attempts);
	}
}
