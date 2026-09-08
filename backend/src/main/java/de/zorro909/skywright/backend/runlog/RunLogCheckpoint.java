package de.zorro909.skywright.backend.runlog;

record RunLogCheckpoint(RunLogArchive.Cursor task, RunLogArchive.Cursor controller, java.time.Instant terminalAt,
		String markerAfter, boolean markersVerified, String taskSourceFailure, String controllerSourceFailure) {
	static RunLogCheckpoint initial() {
		return new RunLogCheckpoint(RunLogArchive.Cursor.initial(), RunLogArchive.Cursor.initial(), null, null, false,
				null, null);
	}

	RunLogArchive.Cursor stream(String name) {
		return switch (name) {
			case "task" -> task;
			case "controller" -> controller;
			default -> throw new IllegalArgumentException("Invalid archive stream");
		};
	}

	RunLogCheckpoint with(String name, RunLogArchive.Cursor value) {
		stream(name);
		return name.equals("task")
				? new RunLogCheckpoint(value, controller, terminalAt, markerAfter, markersVerified, taskSourceFailure,
						controllerSourceFailure)
				: new RunLogCheckpoint(task, value, terminalAt, markerAfter, markersVerified, taskSourceFailure,
						controllerSourceFailure);
	}

	RunLogCheckpoint sourceFailure(String stream, String failure) {
		stream(stream);
		return new RunLogCheckpoint(task, controller, terminalAt, markerAfter, markersVerified,
				stream.equals("task") ? failure : taskSourceFailure,
				stream.equals("controller") ? failure : controllerSourceFailure);
	}

	String sourceFailure(String stream) {
		stream(stream);
		return stream.equals("task") ? taskSourceFailure : controllerSourceFailure;
	}

	boolean ready() {
		return task.ready() && controller.ready();
	}

	RunLogCheckpoint terminal(java.time.Instant now) {
		return terminalAt == null ? new RunLogCheckpoint(task, controller, now, markerAfter, markersVerified,
				taskSourceFailure, controllerSourceFailure) : this;
	}

	RunLogCheckpoint markers(String after, boolean complete) {
		return new RunLogCheckpoint(task, controller, terminalAt, after, complete, taskSourceFailure,
				controllerSourceFailure);
	}
}
