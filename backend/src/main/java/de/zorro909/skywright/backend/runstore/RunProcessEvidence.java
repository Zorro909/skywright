package de.zorro909.skywright.backend.runstore;

import java.time.Instant;
import java.util.List;

/** Verified process-originated evidence, read through without database replication. */
public record RunProcessEvidence(List<Attempt> attempts, String historyHead, int recoveryDebt, Instant exhaustedAt) {

	public RunProcessEvidence {
		attempts = List.copyOf(attempts);
	}

	public record Attempt(String attemptId, String cause, Long lastCommittedStep, Long durableStep,
			String checkpointReference) {
	}

	public Attempt latestAttempt() {
		return attempts.isEmpty() ? null : attempts.getLast();
	}
}
