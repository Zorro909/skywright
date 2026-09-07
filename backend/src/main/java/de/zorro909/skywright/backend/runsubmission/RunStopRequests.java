package de.zorro909.skywright.backend.runsubmission;

import java.time.Instant;

/** Idempotent Run Store projection; receipt time comes from the immutable object. */
public interface RunStopRequests {

	Instant deliver(AcceptedRun run, RunCommand command);

}
