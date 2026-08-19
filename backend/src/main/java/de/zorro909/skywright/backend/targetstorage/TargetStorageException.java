package de.zorro909.skywright.backend.targetstorage;

class TargetStorageException extends RuntimeException {

	private final String code;

	TargetStorageException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}
