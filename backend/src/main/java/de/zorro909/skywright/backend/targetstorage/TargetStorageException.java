package de.zorro909.skywright.backend.targetstorage;

public class TargetStorageException extends RuntimeException {

	private final String code;

	TargetStorageException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
