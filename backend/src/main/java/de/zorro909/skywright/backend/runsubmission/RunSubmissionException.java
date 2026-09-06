package de.zorro909.skywright.backend.runsubmission;

import java.util.List;
import de.zorro909.skywright.backend.rundefinition.RunDefinitionFailure;

final class RunSubmissionException extends RuntimeException {

	private final String code;

	private final int status;

	private final List<RunDefinitionFailure> failures;

	RunSubmissionException(String code, int status) {
		this(code, status, List.of());
	}

	RunSubmissionException(String code, int status, List<RunDefinitionFailure> failures) {
		super(code);
		this.code = code;
		this.status = status;
		this.failures = List.copyOf(failures);
	}

	String code() {
		return code;
	}

	int status() {
		return status;
	}

	List<RunDefinitionFailure> failures() {
		return failures;
	}

}
