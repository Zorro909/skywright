package de.zorro909.skywright.backend.trainingproject;

class TrainingProjectException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String code;

	TrainingProjectException(String code, String message) {
		super(message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}
