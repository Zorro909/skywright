package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.orchestration.OperationOutcome.ManagedJobStatus;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Source-supported execution measurements, independent of lifecycle classification. */
record RunExecutionObservation(Long spanMillis, String spanSource, Integer recoveryCount,
		boolean minimumRecoveryCount) {
	static RunExecutionObservation read(ManagedJobStatus live, List<RetainedSkyPilotFact> facts, boolean terminal,
			Instant at) {
		if (live != null) {
			Long span = span(live.startedAt(), live.endedAt() == null ? at.toEpochMilli() / 1000.0 : live.endedAt());
			return new RunExecutionObservation(span, span == null ? "unavailable" : "live", live.recoveryCount(),
					false);
		}
		if (!terminal)
			return new RunExecutionObservation(null, "unavailable", null, false);
		var qualified = facts.stream().filter(RetainedSkyPilotFact::completeUniqueObservation).toList();
		var end = qualified.stream()
			.filter(f -> f.kind() == RetainedSkyPilotFact.Kind.TERMINATION && time(f) != null)
			.max(Comparator.comparing(RunExecutionObservation::time))
			.orElse(null);
		Long span = null;
		if (end != null) {
			Double start = qualified.stream()
				.filter(f -> f.kind() == RetainedSkyPilotFact.Kind.EXECUTION_STARTED && group(f).equals(group(end))
						&& time(f) != null)
				.map(RunExecutionObservation::time)
				.min(Double::compare)
				.orElse(null);
			span = span(start, time(end));
		}
		Integer recoveries = qualified.stream()
			.filter(f -> f.kind() == RetainedSkyPilotFact.Kind.RECOVERY)
			.map(f -> positiveCount(f.payload().get("recoveryCount")))
			.filter(java.util.Objects::nonNull)
			.max(Integer::compare)
			.orElse(null);
		return new RunExecutionObservation(span, span == null ? "unavailable" : "retained", recoveries,
				recoveries != null);
	}

	private static String group(RetainedSkyPilotFact fact) {
		int separator = fact.sourceEventIdentity().lastIndexOf(':');
		return separator < 0 ? fact.sourceEventIdentity() : fact.sourceEventIdentity().substring(0, separator);
	}

	private static Double time(RetainedSkyPilotFact fact) {
		try {
			double value = Double.parseDouble(fact.payload().get("sourceTime"));
			return Double.isFinite(value) && value >= 0 ? value : null;
		}
		catch (RuntimeException invalid) {
			return null;
		}
	}

	private static Integer positiveCount(String value) {
		try {
			int count = Integer.parseInt(value);
			return count > 0 ? count : null;
		}
		catch (RuntimeException invalid) {
			return null;
		}
	}

	private static Long span(Double start, Double end) {
		if (start == null || end == null || !Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end < start
				|| (end - start) * 1000 >= Long.MAX_VALUE)
			return null;
		return (long) ((end - start) * 1000);
	}
}
