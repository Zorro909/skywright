package de.zorro909.skywright.backend.runlog;

import java.util.UUID;

interface RunLogSource {

	RunLogArchive.Page fetch(UUID runId, String stream, RunLogArchive.Cursor cursor);

	final class Unavailable extends RuntimeException {

		Unavailable(String reason) {
			super(reason);
		}

	}

}
