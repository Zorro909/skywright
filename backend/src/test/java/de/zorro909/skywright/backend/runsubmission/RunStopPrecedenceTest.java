package de.zorro909.skywright.backend.runsubmission;

import static org.assertj.core.api.Assertions.assertThat;
import de.zorro909.skywright.backend.runlifecycle.RunControlDecisions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RunStopPrecedenceTest {

	@ParameterizedTest
	@CsvSource({ "CEILING_STOP,policy_stopped,true,true", "CEILING_STOP,cancelled,false,false",
			"CEILING_STOP,,true,false", "CEILING_STOP,,false,true", "CANCELLATION_REQUEST,policy_stopped,true,false",
			"CANCELLATION_REQUEST,cancelled,true,true", "CANCELLATION_REQUEST,,true,true" })
	void finalProcessCauseWinsOverLaterControlIntent(RunCommand.Kind kind, String cause, boolean cancellation,
			boolean effected) {
		var decisions = cancellation
				? List.of(new RunControlDecisions.Decision(UUID.randomUUID(),
						RunControlDecisions.Kind.CANCELLATION_REQUEST, Instant.now()))
				: List.<RunControlDecisions.Decision>of();
		assertThat(RunCommandDelivery.stopEffect(kind, cause, decisions)).isEqualTo(effected);
	}

}
