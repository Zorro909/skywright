package de.zorro909.skywright.backend.runlifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunExecutionObservationTest {

	private static final Instant AT = Instant.parse("2026-09-07T10:00:00Z");

	@Test
	void retainsOnlySupportedTerminalMeasurementsAndLabelsRecoveryLowerBound() {
		var facts = List.of(
				fact(RetainedSkyPilotFact.Kind.EXECUTION_STARTED, "job:task:generation:100",
						Map.of("sourceTime", "100")),
				fact(RetainedSkyPilotFact.Kind.TERMINATION, "job:task:generation:150", Map.of("sourceTime", "150")),
				fact(RetainedSkyPilotFact.Kind.RECOVERY, "job:task:generation:130",
						Map.of("sourceTime", "130", "recoveryCount", "3")));
		var result = RunExecutionObservation.read(null, facts, true, AT);
		assertThat(result.spanMillis()).isEqualTo(50_000);
		assertThat(result.spanSource()).isEqualTo("retained");
		assertThat(result.recoveryCount()).isEqualTo(3);
		assertThat(result.minimumRecoveryCount()).isTrue();
		var inFlight = RunExecutionObservation.read(null, facts, false, AT);
		assertThat(inFlight.spanMillis()).isNull();
		assertThat(inFlight.recoveryCount()).isNull();
	}

	@Test
	void refusesMismatchedGenerationsAndDoesNotInferZeroRecoveries() {
		var facts = List.of(
				fact(RetainedSkyPilotFact.Kind.EXECUTION_STARTED, "job:task:old:100", Map.of("sourceTime", "100")),
				fact(RetainedSkyPilotFact.Kind.TERMINATION, "job:task:new:150", Map.of("sourceTime", "150")));
		var result = RunExecutionObservation.read(null, facts, true, AT);
		assertThat(result.spanMillis()).isNull();
		assertThat(result.recoveryCount()).isNull();
	}

	@Test
	void neverExtendsTerminalOrUnknownObservationsWithoutAnEndTimestamp() {
		for (String status : List.of("SUCCEEDED", "FAILED", "FAILED_SETUP", "FAILED_PRECHECKS", "FAILED_NO_RESOURCE",
				"FAILED_CONTROLLER", "CANCELLED", "UNKNOWN")) {
			var live = job(status);
			assertThat(RunExecutionObservation.read(live, List.of(), false, AT).spanMillis()).as(status).isNull();
			assertThat(RunExecutionObservation.read(live, List.of(), false, AT.plusSeconds(60)).spanMillis()).as(status)
				.isNull();
		}
		assertThat(RunExecutionObservation.read(job(null), List.of(), false, AT).spanMillis()).isNull();
		assertThat(RunExecutionObservation.read(job("RUNNING"), List.of(), false, AT).spanMillis()).isPositive();
		assertThat(RunExecutionObservation.read(job("RUNNING"), List.of(), true, AT).spanMillis()).isNull();
	}

	private de.zorro909.skywright.backend.orchestration.OperationOutcome.ManagedJobStatus job(String status) {
		return new de.zorro909.skywright.backend.orchestration.OperationOutcome.ManagedJobStatus(42L, "skywright-run",
				status, 0, 0, 100., 110., null, null, "generation", null, null, null, null, null, null);
	}

	private RetainedSkyPilotFact fact(RetainedSkyPilotFact.Kind kind, String identity, Map<String, String> payload) {
		return new RetainedSkyPilotFact(UUID.fromString("00000000-0000-4000-8000-000000000083"), kind, identity,
				payload, AT, true);
	}

}
