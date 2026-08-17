package de.zorro909.skywright.backend.projectversion;

/** Stable caller-facing failure to use an otherwise verified version. */
public final class ProjectVersionException extends IllegalArgumentException {

	private final ProjectVersionFailure failure;

	public ProjectVersionException(ProjectVersionFailure failure) {
		super(failure.code());
		this.failure = failure;
	}

	public ProjectVersionFailure failure() {
		return this.failure;
	}

}
