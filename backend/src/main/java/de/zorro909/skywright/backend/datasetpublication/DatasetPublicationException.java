package de.zorro909.skywright.backend.datasetpublication;

final class DatasetPublicationException extends RuntimeException {

	private final String errorCode;

	private final boolean retryable;

	DatasetPublicationException(String errorCode, String message, boolean retryable) {
		super(errorCode + ": " + message);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	String errorCode() {
		return this.errorCode;
	}

	boolean retryable() {
		return this.retryable;
	}

}
