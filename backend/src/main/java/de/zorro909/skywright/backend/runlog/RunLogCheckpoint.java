package de.zorro909.skywright.backend.runlog;

record RunLogCheckpoint(RunLogArchive.Cursor task, RunLogArchive.Cursor controller, java.time.Instant terminalAt,
		String markerAfter, boolean markersVerified) {
	static RunLogCheckpoint initial() {
		return new RunLogCheckpoint(RunLogArchive.Cursor.initial(), RunLogArchive.Cursor.initial(), null, null, false);
	}

	RunLogArchive.Cursor stream(String name) {
		return name.equals("task") ? task : controller;
	}

	RunLogCheckpoint with(String name, RunLogArchive.Cursor value) {
		return name.equals("task") ? new RunLogCheckpoint(value, controller, terminalAt, markerAfter, markersVerified)
				: new RunLogCheckpoint(task, value, terminalAt, markerAfter, markersVerified);
	}

	boolean ready() {
		return task.ready() && controller.ready();
	}

	RunLogCheckpoint terminal(java.time.Instant now) {
		return terminalAt == null ? new RunLogCheckpoint(task, controller, now, markerAfter, markersVerified) : this;
	}

	RunLogCheckpoint markers(String after, boolean complete) {
		return new RunLogCheckpoint(task, controller, terminalAt, after, complete);
	}
}
