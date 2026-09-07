package de.zorro909.skywright.backend.runlifecycle;

import de.zorro909.skywright.backend.runstore.RunProcessEvidence;
import de.zorro909.skywright.backend.runsubmission.AcceptedRun;

/** Process-owned evidence is fetched from the Run Record's current Run Store location. */
public interface RunProcessReads {

	RunProcessEvidence read(AcceptedRun run);

}
