package de.zorro909.skywright.backend.rundefinition;

/** Stable failure reported by a Run Definition storage-selection adapter. */
public final class RunDefinitionStorageException extends RuntimeException {

	private final String code;

	public RunDefinitionStorageException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
