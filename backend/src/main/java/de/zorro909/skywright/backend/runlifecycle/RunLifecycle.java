package de.zorro909.skywright.backend.runlifecycle;

public enum RunLifecycle {

	WAITING, RUNNING, INTERRUPTED, FINISHED, FAILED, CANCELLED;

	public boolean terminal() {
		return this == FINISHED || this == FAILED || this == CANCELLED;
	}

}
